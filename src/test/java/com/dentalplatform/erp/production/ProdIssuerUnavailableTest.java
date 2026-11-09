package com.dentalplatform.erp.production;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProdIssuerUnavailableTest {
    static final AtomicInteger discoveryRequests = new AtomicInteger();
    static final HttpServer issuer = unavailableIssuer();
    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    static HttpServer unavailableIssuer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                discoveryRequests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl("postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("KEYCLOAK_ISSUER_URI", () -> "http://127.0.0.1:" + issuer.getAddress().getPort() + "/realm");
        r.add("OPENAI_API_KEY", () -> "");
        r.add("GEMINI_API_KEY", () -> "");
        r.add("AI_PROVIDER", () -> "openai");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("spring.rabbitmq.listener.direct.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.billing-saga.outbox.enabled", () -> false);
    }

    @Test
    void startupAvoidsIssuerAndProtectedRequestsNeverBypassAuthentication() throws Exception {
        assertThat(discoveryRequests.get()).as("no issuer access during startup").isZero();
        var client = HttpClient.newHttpClient();
        for (String endpoint : new String[] {"/graphql", "/mcp"}) {
        for (String header : new String[] {"", "X-Dev-User", "Authorization"}) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ listarRoles { codigo } }\"}"));
            if (header.equals("X-Dev-User")) request.header(header, "admin-scz");
            if (header.equals("Authorization")) request.header(header, "Bearer malformed");
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(header).isEqualTo(401);
            assertThat(response.body()).as(endpoint + " " + header).isEmpty();
        }
        }
    }

    @AfterAll static void stopIssuer() { issuer.stop(0); }
}
