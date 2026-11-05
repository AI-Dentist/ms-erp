package com.dentalplatform.erp.financebilling.storage;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.financebilling.infrastructure.persistence.ReciboRepositoryAdapter;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** S6 through public GraphQL plus a loopback S3 fixture: every accepted object keeps a durable owner. */
@WithMockUser(username = "admin", roles = "ADMIN")
class ReceiptArtifactOwnershipTest extends IntegrationTestBase {
    private static final String BUCKET = "private-receipts";
    private static final String RESERVED = "RECIBO_PDF_RESERVADO";
    private static final HttpServer SERVER;
    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> OWNED_AT_UPLOAD = new ConcurrentHashMap<>();
    private static final Map<String, Integer> PUTS = new ConcurrentHashMap<>();
    private static volatile int status = 200;
    private static volatile Function<String, Boolean> ownershipProbe = id -> false;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                if ("PUT".equals(exchange.getRequestMethod()) && path.endsWith(".pdf")) {
                    String id = path.substring(path.lastIndexOf('/') + 1, path.length() - ".pdf".length());
                    // Observed through an independent authenticated read before the object is accepted.
                    OWNED_AT_UPLOAD.put(id, ownershipProbe.apply(id));
                    if (status == 200) {
                        OBJECTS.put(path, body);
                        PUTS.merge(path, 1, Integer::sum);
                    }
                }
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    byte[] object = OBJECTS.get(path);
                    if (object != null) {
                        exchange.getResponseHeaders().add("Content-Length", String.valueOf(object.length));
                        exchange.getResponseHeaders().add("Content-Type", "application/pdf");
                        exchange.getResponseHeaders().add("ETag", "\"receipt-etag\"");
                        exchange.getResponseHeaders().add("Last-Modified", "Wed, 04 Nov 2026 00:00:00 GMT");
                    }
                    exchange.sendResponseHeaders(object == null ? 404 : 200, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("ETag", "\"receipt-etag\"");
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                byte[] error = "<Error><Code>AccessDenied</Code><Message>Denied</Message></Error>".getBytes();
                exchange.sendResponseHeaders(status, status == 200 ? -1 : error.length);
                if (status != 200) exchange.getResponseBody().write(error);
                exchange.close();
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("erp.receipts.mode", () -> "minio");
        registry.add("erp.receipts.minio.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("erp.receipts.minio.access-key", () -> "test-access");
        registry.add("erp.receipts.minio.secret-key", () -> "test-secret-not-production");
        registry.add("erp.receipts.minio.bucket", () -> BUCKET);
    }

    @AfterAll
    static void stop() { SERVER.stop(0); }

    @MockitoSpyBean ReciboRepositoryAdapter receipts;

    enum Fault { LINK_FAILS, COMMIT_FAILS_AFTER_LINK }

    @BeforeEach
    void reset() {
        status = 200;
        ownershipProbe = id -> asAdmin(() -> !reservations(id).isEmpty());
    }

    private <T> T asAdmin(java.util.function.Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        SecurityContextHolder.setContext(context);
        try { return action.get(); } finally { SecurityContextHolder.setContext(previous); }
    }

    private String key(String receipt) { return "/" + BUCKET + "/" + receipt + ".pdf"; }

    /** Own window only: other classes link receipts this S3 adapter does not resolve. */
    private record Payment(String order, String quota, String since) { }

    private Payment order() {
        String since = OffsetDateTime.now().minusSeconds(5).toString();
        var response = graphQl.document("""
                mutation { crearOrdenCobroManual(input: {pacienteId: "60000000-0000-0000-0000-000000000001",
                  items: [{servicioId: "70000000-0000-0000-0000-000000000001", cantidad: 1}]}) { id cuotas { id } } }
                """).execute();
        return new Payment(response.path("crearOrdenCobroManual.id").entity(String.class).get(),
                response.path("crearOrdenCobroManual.cuotas[0].id").entity(String.class).get(), since);
    }

    private static final String PAY = """
            mutation($quota: UUID!) { cobrarCuotaEnEfectivo(input: {cuotaId: $quota,
              usuarioCajeroId: "20000000-0000-0000-0000-000000000002", ciNitCliente: "123", razonSocialCliente: "Prueba"}) {
              id pdfUrlS3 } }""";

    private String pay(Payment payment) {
        return graphQl.document(PAY).variable("quota", payment.quota()).execute()
                .path("cobrarCuotaEnEfectivo.pdfUrlS3").valueIsNull()
                .path("cobrarCuotaEnEfectivo.id").entity(String.class).get();
    }

    private List<Map<String, Object>> reservations(String receipt) {
        return graphQl.document("{ listarAuditLogs(limite: 500) { accion entidadAfectada entidadId detalleJson } }").execute()
                .path("listarAuditLogs").entityList(Map.class).get().stream()
                .map(row -> (Map<String, Object>) row)
                .filter(row -> RESERVED.equals(row.get("accion")) && receipt.equals(row.get("entidadId")))
                .toList();
    }

    private List<Map<String, Object>> cashflowReceipts(Payment payment, String receipt) {
        String start = payment.since();
        return graphQl.document("""
                query($start: DateTime!) { obtenerFlujoCaja(fechaInicio: $start, fechaFin: "2100-01-01T00:00:00Z") {
                  movimientos { recibo { id pdfUrlS3 } } } }""").variable("start", start).execute()
                .path("obtenerFlujoCaja.movimientos[?(@.recibo.id=='" + receipt + "')].recibo")
                .entityList(Map.class).get().stream().map(row -> (Map<String, Object>) row).toList();
    }

    private void assertPaidOnce(Payment payment, String receipt) {
        graphQl.document("query($id: UUID!) { obtenerOrdenCobroDetalle(id: $id) { saldoPendienteBs estado cuotas { estado } } }")
                .variable("id", payment.order()).execute()
                .path("obtenerOrdenCobroDetalle.saldoPendienteBs").entity(Double.class).isEqualTo(0.0)
                .path("obtenerOrdenCobroDetalle.estado").entity(String.class).isEqualTo("PAGADO")
                .path("obtenerOrdenCobroDetalle.cuotas[0].estado").entity(String.class).isEqualTo("PAGADA");
        assertThat(cashflowReceipts(payment, receipt)).hasSize(1);
    }

    private void assertOwned(String receipt) {
        assertThat(OBJECTS.get(key(receipt))).as("accepted private object").isNotNull()
                .satisfies(bytes -> assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF"));
        assertThat(OWNED_AT_UPLOAD.get(receipt)).as("ownership committed before the object was accepted").isTrue();
        var owners = reservations(receipt);
        assertThat(owners).hasSize(1);
        assertThat(owners.getFirst().get("entidadAfectada")).isEqualTo("recibos");
        assertThat(owners.getFirst().get("detalleJson").toString()).contains("s3://" + BUCKET + "/" + receipt + ".pdf");
    }

    private void assertDuplicatePaymentDoesNotAddArtifacts(Payment payment, String receipt) {
        int objects = OBJECTS.size();
        graphQl.document(PAY).variable("quota", payment.quota()).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        assertThat(OBJECTS).hasSize(objects);
        assertThat(reservations(receipt)).hasSize(1);
        assertPaidOnce(payment, receipt);
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void failureAfterUploadLeavesTheObjectDurablyOwnedAndThePaymentCommittedOnce(Fault fault) {
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            Recibo receipt = call.getArgument(0);
            if (receipt.pdfUrlS3() == null || !unavailable.get()) return call.callRealMethod();
            if (fault == Fault.LINK_FAILS) throw new TransientDataAccessResourceException("Synthetic link outage");
            Object saved = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    throw new TransientDataAccessResourceException("Synthetic commit outage after link");
                }
            });
            return saved;
        }).when(receipts).guardar(any(Recibo.class));
        Payment payment = order();
        String receipt = pay(payment);
        assertPaidOnce(payment, receipt);
        assertOwned(receipt);
        assertThat(cashflowReceipts(payment, receipt).getFirst().get("pdfUrlS3")).as("never linked, never downloadable").isNull();
        assertDuplicatePaymentDoesNotAddArtifacts(payment, receipt);
        int objectCount = OBJECTS.size();
        unavailable.set(false); // The same public download query is retried after persistence recovers.
        Object repaired = cashflowReceipts(payment, receipt).getFirst().get("pdfUrlS3");
        assertThat(repaired).as("retry links the already uploaded receipt object").isNotNull();
        assertThat(repaired.toString()).contains(key(receipt) + "?", "X-Amz-Expires=300");
        assertThat(cashflowReceipts(payment, receipt).getFirst().get("pdfUrlS3")).isNotNull();
        assertThat(OBJECTS).hasSize(objectCount);
        assertThat(PUTS.get(key(receipt))).as("repair must not upload again").isEqualTo(1);
        assertThat(reservations(receipt)).hasSize(1);
        assertPaidOnce(payment, receipt);
    }

    @Test
    void successfulUploadIsOwnedAndLinkedForAuthorizedDownload() {
        Payment payment = order();
        String receipt = pay(payment);
        assertPaidOnce(payment, receipt);
        assertOwned(receipt);
        assertThat(cashflowReceipts(payment, receipt).getFirst().get("pdfUrlS3").toString())
                .startsWith("http://127.0.0.1:").contains(key(receipt) + "?", "X-Amz-Expires=300");
        assertDuplicatePaymentDoesNotAddArtifacts(payment, receipt);
    }

    @Test
    void rejectedUploadStoresNoObjectAndKeepsThePaymentWithoutArtifact() {
        status = 403;
        Payment payment = order();
        String receipt = pay(payment);
        assertPaidOnce(payment, receipt);
        assertThat(OBJECTS).doesNotContainKey(key(receipt));
        assertThat(cashflowReceipts(payment, receipt).getFirst().get("pdfUrlS3")).isNull();
    }
}
