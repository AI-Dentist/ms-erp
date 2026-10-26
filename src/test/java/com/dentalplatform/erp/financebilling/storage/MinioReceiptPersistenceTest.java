package com.dentalplatform.erp.financebilling.storage;

import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@WithMockUser(roles = "ADMIN")
class MinioReceiptPersistenceTest extends IntegrationTestBase {
    static final HttpServer SERVER;
    static volatile int status = 200;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("ETag", "\"pdf-etag\"");
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                byte[] body = "<Error><Code>AccessDenied</Code><Message>Denied</Message></Error>".getBytes();
                exchange.sendResponseHeaders(status, status == 200 ? -1 : body.length);
                if (status != 200) exchange.getResponseBody().write(body);
                exchange.close();
            });
            SERVER.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void minio(DynamicPropertyRegistry r) {
        r.add("erp.receipts.mode", () -> "minio");
        r.add("erp.receipts.minio.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("erp.receipts.minio.access-key", () -> "test-access");
        r.add("erp.receipts.minio.secret-key", () -> "test-secret-not-production");
        r.add("erp.receipts.minio.bucket", () -> "private-receipts");
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @Test void paymentStoresStableReferenceAndAuthorizedQuerySignsIt() { exercise(200); }
    @Test void deniedStorageLeavesPaymentCommittedAndArtifactNull() { exercise(403); }
    void exercise(int responseStatus) {
        status = responseStatus;
        String start = java.time.OffsetDateTime.now().toString();
        String order = graphQl.document("""
            mutation { crearOrdenCobroManual(input: {pacienteId: "60000000-0000-0000-0000-000000000001",
              items: [{servicioId: "70000000-0000-0000-0000-000000000001", cantidad: 1}]}) { id cuotas { id } } }
            """).execute().path("crearOrdenCobroManual.id").entity(String.class).get();
        UUID quota = jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ?", UUID.class, UUID.fromString(order));
        String id = graphQl.document("""
            mutation($quota: UUID!) { cobrarCuotaEnEfectivo(input: {cuotaId: $quota,
              usuarioCajeroId: "20000000-0000-0000-0000-000000000002", ciNitCliente: "123", razonSocialCliente: "Prueba"}) { id pdfUrlS3 } }
            """).variable("quota", quota.toString()).execute().path("cobrarCuotaEnEfectivo.pdfUrlS3").valueIsNull()
                .path("cobrarCuotaEnEfectivo.id").entity(String.class).get();
        String stored = jdbc.queryForObject("SELECT pdf_url_s3 FROM recibos WHERE id = ?", String.class, UUID.fromString(id));
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota)).isEqualTo("PAGADA");
        assertThat(jdbc.queryForObject("SELECT saldo_pendiente_bs FROM ordenes_cobro WHERE id = ?", java.math.BigDecimal.class,
                UUID.fromString(order))).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ?", Integer.class, quota)).isEqualTo(1);
        if (responseStatus == 403) { assertThat(stored).isNull(); return; }
        assertThat(stored).isEqualTo("s3://private-receipts/" + id + ".pdf");
        graphQl.document("""
            query($start: DateTime!) { obtenerFlujoCaja(fechaInicio: $start, fechaFin: "2100-01-01T00:00:00Z") {
                movimientos { recibo { id pdfUrlS3 } }
            } }
            """).variable("start", start).execute().path("obtenerFlujoCaja.movimientos[?(@.recibo.id=='" + id + "')].recibo.pdfUrlS3")
                .entityList(String.class).satisfies(urls -> {
                    assertThat(urls).hasSize(1);
                    assertThat(urls.getFirst()).startsWith("http://127.0.0.1:").contains("/private-receipts/" + id + ".pdf?", "X-Amz-Expires=300");
                });
        assertThat(jdbc.queryForObject("SELECT pdf_url_s3 FROM recibos WHERE id = ?", String.class, UUID.fromString(id))).isEqualTo(stored);
    }
}
