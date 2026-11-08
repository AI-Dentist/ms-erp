package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import com.dentalplatform.erp.financebilling.infrastructure.external.N8nWebhookAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WithMockUser(username = "admin", roles = "ADMIN")
class N8nPaymentIntegrationTest extends IntegrationTestBase {
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicReference<String> payload = new AtomicReference<>();
    static final HttpServer server = startServer();
    static final AtomicReference<Runnable> inspectCommit = new AtomicReference<>(() -> { });
    @Autowired WebApplicationContext context;
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;

    static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/notify", exchange -> {
                inspectCommit.get().run();
                calls.incrementAndGet();
                payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void notificationProperties(DynamicPropertyRegistry registry) {
        registry.add("erp.n8n.enabled", () -> "true");
        registry.add("erp.n8n.webhook-secret", () -> "synthetic-test-secret");
        registry.add("erp.pagofacil.webhook-secret", () -> "n8n-local-test-secret");
        registry.add("erp.n8n.webhook-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/notify");
    }

    @AfterAll
    static void closeServer() { server.stop(0); }

    @Test
    void disabledAdapterSendsNothing() {
        int before = calls.get();
        new N8nWebhookAdapter(false, "http://127.0.0.1:" + server.getAddress().getPort() + "/notify", 500, 500)
                .notificar(null);
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void qrNotificationFailsAfterCommitAndDuplicateDoesNotNotifyAgain() throws Exception {
        String order = graphQl.document("mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) { id } }")
                .variable("input", Map.of("pacienteId", "60000000-0000-0000-0000-000000000001", "items",
                        List.of(Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.id").entity(String.class).get();
        UUID quota = jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ?", UUID.class, UUID.fromString(order));
        String reference = graphQl.document("mutation { generarQrPagoFacil(cuotaId: \"" + quota
                + "\", ciNitCliente: \"SYNTHETIC\", razonSocialCliente: \"Synthetic test\") { pagofacilTransactionId } }")
                .execute().path("generarQrPagoFacil.pagofacilTransactionId").entity(String.class).get();
        var committedAtSend = new AtomicBoolean();
        inspectCommit.set(() -> committedAtSend.set(jdbc.queryForObject(
                "SELECT count(*) FROM transacciones_qr WHERE pagofacil_transaction_id = ? AND estado = 'CONFIRMADO'",
                Integer.class, reference) == 1));
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String body = "{\"transactionId\":\"" + reference + "\",\"amountBs\":100,\"confirmedAt\":\"" + OffsetDateTime.now() + "\"}";
        int before = calls.get();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/webhooks/pagofacil").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Local-Mock-Secret", "n8n-local-test-secret").content(body)).andExpect(status().isOk());
        }
        assertThat(calls.get()).isEqualTo(before + 1);
        assertThat(committedAtSend.get()).isTrue();
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota)).isEqualTo("PAGADA");
        inspectCommit.set(() -> { });
    }

    @Test
    void failingHttpAfterCommitPreservesCashPaymentEvenIfPdfAlsoFails() {
        String order = graphQl.document("mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) { id } }")
                .variable("input", Map.of("pacienteId", "60000000-0000-0000-0000-000000000001", "items",
                        List.of(Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.id").entity(String.class).get();
        UUID quota = jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ?", UUID.class, UUID.fromString(order));
        when(pdf.generar(any())).thenThrow(new IllegalStateException("PDF test failure"));
        int before = calls.get();
        var committedAtSend = new AtomicBoolean();
        inspectCommit.set(() -> committedAtSend.set(jdbc.queryForObject(
                "SELECT count(*) FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = ?",
                Integer.class, quota) == 1));
        graphQl.document("mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { id } }")
                .variable("input", Map.of("cuotaId", quota.toString(), "usuarioCajeroId", "20000000-0000-0000-0000-000000000002",
                        "ciNitCliente", "SYNTHETIC-ONLY", "razonSocialCliente", "Synthetic local test"))
                .execute().path("cobrarCuotaEnEfectivo.id").hasValue();
        assertThat(calls.get()).isEqualTo(before + 1);
        assertThat(committedAtSend.get()).isTrue();
        inspectCommit.set(() -> { });
        assertThat(payload.get()).contains("reciboId", "numeroRecibo").doesNotContain("SYNTHETIC-ONLY", "Synthetic local test");
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota)).isEqualTo("PAGADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = ?", Integer.class, quota)).isEqualTo(1);
    }
}
