package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.financebilling.infrastructure.external.SpringAiSqlGeneratorAdapter;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.dentalplatform.erp.financebilling.agent.AgentSupport.EXECUTE;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiProviderIntegrationTest extends IntegrationTestBase {
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static final List<String> AUTH = new CopyOnWriteArrayList<>();
    static final HttpServer SERVER = start();
    @Autowired SpringAiSqlGeneratorAdapter sql;

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1beta/openai/chat/completions", exchange -> {
                REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AUTH.add(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = JsonMapper.builder().build().writeValueAsBytes(Map.of("id", "test", "object", "chat.completion",
                        "created", 1, "model", "test", "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", "SELECT code FROM report_services LIMIT 1"),
                                "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource static void provider(DynamicPropertyRegistry r) {
        r.add("AI_PROVIDER", () -> "gemini");
        r.add("OPENAI_API_KEY", () -> "unused-openai-key");
        r.add("GEMINI_API_KEY", () -> "gemini-loopback-key");
        r.add("GEMINI_MODEL", () -> "gemini-loopback-model");
        r.add("spring.ai.openai.chat.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1beta/openai");
        r.add("spring.ai.retry.max-attempts", () -> 1);
    }
    @AfterAll static void stop() { SERVER.stop(0); }

    @Test @WithMockUser(username = "admin", roles = "ADMIN")
    void agentAndSqlUseSelectedGeminiCredentialsModelAndCompatiblePath() {
        graphQl = graphQl.mutate().responseTimeout(java.time.Duration.ofSeconds(15)).build();
        graphQl.document(EXECUTE).variable("i", "Dame un resumen").execute()
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .isEqualTo("SELECT code FROM report_services LIMIT 1");
        assertThat(sql.generate("Precios")).isEqualTo("SELECT code FROM report_services LIMIT 1");
        assertThat(REQUESTS).hasSize(2).allSatisfy(request -> assertThat(request).contains("gemini-loopback-model"));
        assertThat(AUTH).containsExactly("Bearer gemini-loopback-key", "Bearer gemini-loopback-key");
    }
}
