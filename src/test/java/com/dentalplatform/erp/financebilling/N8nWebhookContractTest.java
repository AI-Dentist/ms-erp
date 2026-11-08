package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.infrastructure.external.N8nWebhookAdapter;
import com.dentalplatform.erp.financebilling.application.port.N8nNotificationPort.CobranzaAlerta;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class N8nWebhookContractTest {
    private AnnotationConfigApplicationContext context(String url, String secret) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "erp.n8n.enabled", "true", "erp.n8n.webhook-url", url, "erp.n8n.webhook-secret", secret)));
        context.register(N8nWebhookAdapter.class);
        context.refresh();
        return context;
    }

    @Test
    void missingSecretFailsClosedWithoutSending() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var context = context("http://127.0.0.1:" + server.getAddress().getPort(), "")) {
            var adapter = context.getBean(N8nWebhookAdapter.class);
            assertThat(adapter.habilitado()).isFalse();
            assertThatThrownBy(() -> adapter.notificarCobranza(alert())).isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    void webhookAddsCanonicalFieldsAndAuthenticatesWhileKeepingLegacyFields() throws Exception {
        AtomicReference<String> header = new AtomicReference<>(), body = new AtomicReference<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-ERP-Webhook-Secret"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try (var context = context("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-test-secret")) {
            context.getBean(N8nWebhookAdapter.class).notificarCobranza(alert());
            assertThat(header.get()).isEqualTo("synthetic-test-secret");
            var payload = JsonMapper.builder().build().readTree(body.get());
            assertThat(payload.path("pacienteNombre").asString()).isEqualTo("Paciente de prueba");
            assertThat(payload.path("telefono").asString()).isEqualTo("70000000");
            assertThat(payload.path("fechaVencimiento").asString()).isEqualTo("2026-01-01");
            assertThat(payload.path("concepto").asString()).isEqualTo("Cuota 2 de orden " + alert().ordenCobroId());
            assertThat(payload.path("montoBs").decimalValue()).isEqualByComparingTo("12.50");
            assertThat(payload.path("nombrePaciente").asString()).isEqualTo("Paciente de prueba");
            assertThat(payload.path("telefonoWhatsapp").asString()).isEqualTo("70000000");
            assertThat(payload.path("vencimiento").asString()).isEqualTo("2026-01-01");
        } finally { server.stop(0); }
    }

    private CobranzaAlerta alert() {
        return new CobranzaAlerta(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), "Paciente de prueba", "70000000",
                2, new BigDecimal("12.50"), LocalDate.of(2026, 1, 1), 2, null);
    }
}
