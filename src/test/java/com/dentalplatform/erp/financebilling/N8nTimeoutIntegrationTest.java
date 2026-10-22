package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.infrastructure.external.N8nWebhookAdapter;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WithMockUser(username = "admin", roles = "ADMIN")
class N8nTimeoutIntegrationTest extends IntegrationTestBase {
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicReference<String> PAYLOAD = new AtomicReference<>();
    static final AtomicReference<Runnable> INSPECT = new AtomicReference<>(() -> { });
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer SERVER = server();
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;

    static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/stall", exchange -> {
                try {
                    PAYLOAD.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    CALLS.incrementAndGet();
                    INSPECT.get().run();
                    RELEASE.await(5, TimeUnit.SECONDS); // Never infinite, including on test failure.
                    exchange.sendResponseHeaders(204, -1);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String url() { return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/stall"; }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("erp.n8n.enabled", () -> true);
        r.add("erp.n8n.webhook-url", N8nTimeoutIntegrationTest::url);
        r.add("erp.n8n.connect-timeout-ms", () -> 200);
        r.add("erp.n8n.read-timeout-ms", () -> 300);
    }

    @AfterAll static void close() {
        RELEASE.countDown();
        SERVER.stop(0);
        EXECUTOR.shutdownNow();
    }

    @Test void disabledModeNeverContactsListener() {
        int before = CALLS.get();
        new N8nWebhookAdapter(false, url(), 200, 300).notificar(null);
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void stalledHttpTimesOutAfterCommitWithoutRevertingOrRepeatingPayment() {
        String order = graphQl.document("mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) { id } }")
                .variable("input", Map.of("pacienteId", "60000000-0000-0000-0000-000000000001", "items",
                        List.of(Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.id").entity(String.class).get();
        UUID quota = jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id=?", UUID.class, UUID.fromString(order));
        when(pdf.generar(any())).thenThrow(new IllegalStateException("Synthetic PDF failure"));
        var committed = new AtomicReference<List<Map<String, Object>>>();
        INSPECT.set(() -> committed.set(paymentSnapshot(quota)));
        String pay = "mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { id } }";
        var input = Map.of("cuotaId", quota.toString(), "usuarioCajeroId", "20000000-0000-0000-0000-000000000002",
                "ciNitCliente", "SYNTHETIC-NOT-FOR-HTTP", "razonSocialCliente", "Synthetic timeout test");
        int before = CALLS.get();
        long start = System.nanoTime();
        String receipt = graphQl.document(pay).variable("input", input).execute()
                .path("cobrarCuotaEnEfectivo.id").entity(String.class).get();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(200), Duration.ofSeconds(4));
        assertThat(CALLS.get()).isEqualTo(before + 1);
        assertThat(PAYLOAD.get()).contains(receipt, "recibo.emitido", "numeroRecibo")
                .doesNotContain("SYNTHETIC-NOT-FOR-HTTP", "Synthetic timeout test", quota.toString());
        assertThat(committed.get()).hasSize(1);
        assertThat(committed.get().getFirst()).containsEntry("estado", "PAGADA").containsEntry("receipt", UUID.fromString(receipt));
        assertThat((java.math.BigDecimal) committed.get().getFirst().get("saldo_pendiente_bs")).isZero();
        assertThat(committed.get().getFirst().get("movement").toString()).contains("INGRESO", "EFECTIVO", "COBRO_CUOTA");
        assertThat(paymentSnapshot(quota)).isEqualTo(committed.get());
        graphQl.document(pay).variable("input", input).execute().errors().expect(e -> e.getErrorType() == org.springframework.graphql.execution.ErrorType.BAD_REQUEST).verify();
        assertThat(paymentSnapshot(quota)).isEqualTo(committed.get());
        assertThat(CALLS.get()).isEqualTo(before + 1);
        INSPECT.set(() -> { });
    }

    List<Map<String, Object>> paymentSnapshot(UUID quota) {
        return jdbc.queryForList("""
                SELECT q.estado, o.saldo_pendiente_bs, to_jsonb(m)::text AS movement,
                       r.id AS receipt, to_jsonb(r)::text AS receipt_row
                FROM cuotas_pago q JOIN ordenes_cobro o ON o.id=q.orden_cobro_id
                JOIN movimientos_caja m ON m.cuota_id=q.id JOIN recibos r ON r.movimiento_caja_id=m.id
                WHERE q.id=?
                """, quota);
    }
}
