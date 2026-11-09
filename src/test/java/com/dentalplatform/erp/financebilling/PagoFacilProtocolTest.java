package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort.SolicitudQr;
import com.dentalplatform.erp.financebilling.infrastructure.external.PagoFacilHttpAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

/** Synthetic credentials and loopback provider only; never contacts PagoFacil. */
class PagoFacilProtocolTest {
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=";
    final JsonMapper json = JsonMapper.builder().build();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    HttpServer server;
    String login = "{\"accessToken\":\"synthetic-bearer\",\"expiresInMinutes\":789.71}";
    String services = "[{\"paymentMethodId\":9,\"currencyName\":\"USD\"},{\"paymentMethodId\":4,\"currencyName\":\"BOB\",\"paymentMethodName\":\"QR BOB\"}]";
    String generated;
    String queried = "{\"paymentStatus\":2,\"amount\":\"0.35\",\"currencyId\":2}";
    int error;
    int httpStatus = 200;
    int servicesStatus = 200;
    final List<String> querySecrets = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        generated = "{\"transactionId\":\"provider-123\",\"qrBase64\":\"data:image/png;base64," + PNG
                + "\",\"expirationDate\":\"2027-01-01 10:30:00\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            bodies.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Response-Language")).isEqualTo("es");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json");
            if (path.equals("/login")) {
                assertThat(exchange.getRequestHeaders().getFirst("tcTokenService")).isEqualTo("synthetic-service");
                assertThat(exchange.getRequestHeaders().getFirst("tcTokenSecret")).isEqualTo("synthetic-secret");
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            } else {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-bearer");
                if (path.equals("/query-transaction")) {
                    querySecrets.add(Objects.toString(exchange.getRequestHeaders().getFirst("tcTokenSecret"), ""));
                } else {
                    assertThat(exchange.getRequestHeaders().getFirst("tcTokenSecret")).isNull();
                }
            }
            String values = switch (path) {
                case "/login" -> login;
                case "/list-enabled-services" -> services;
                case "/generate-qr" -> generated;
                case "/query-transaction" -> queried;
                default -> "null";
            };
            byte[] response = ("{\"error\":" + error + ",\"status\":2007,\"values\":" + values + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.equals("/list-enabled-services") ? servicesStatus : httpStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    PagoFacilHttpAdapter adapter(int method, Clock clock) {
        return adapter(method, clock, "https://example.invalid/callback");
    }
    PagoFacilHttpAdapter adapter(int method, Clock clock, String callback) {
        return new PagoFacilHttpAdapter(false, "http://127.0.0.1:" + server.getAddress().getPort(),
                "synthetic-service", "synthetic-secret", method, callback, clock);
    }
    SolicitudQr request(String amount) {
        return new SolicitudQr("ERP-QR-synthetic", new BigDecimal(amount), "12345", "Cliente sintético", "patient-code", "Cuota odontológica");
    }
    @Test void protocolScalesAndCachesCredentialsAndMethodWhileKeepingOriginalRequest() {
        var adapter = adapter(0, Clock.systemUTC());
        var request = request("350.00");
        var qr = adapter.generar(request);
        assertThat(qr.reference()).isEqualTo("provider-123");
        assertThat(qr.imageBase64()).isEqualTo(PNG);
        assertThat(qr.expiration()).isEqualTo(OffsetDateTime.parse("2027-01-01T14:30:00Z"));
        assertThat(request.montoOriginalBs()).isEqualByComparingTo("350.00");
        JsonNode body = bodies.get(2);
        assertThat(body.properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("paymentMethod", "clientName", "documentType", "documentId", "phoneNumber", "email", "paymentNumber", "amount", "currency", "clientCode", "callbackUrl", "orderDetail");
        assertThat(body.path("paymentMethod").asInt()).isEqualTo(4);
        assertThat(body.path("clientName").asString()).isEqualTo("Cliente sintético");
        assertThat(body.path("documentType").asInt()).isEqualTo(1);
        assertThat(body.path("documentId").asString()).isEqualTo("12345");
        assertThat(body.path("phoneNumber").asString()).isEqualTo("70000000");
        assertThat(body.path("email").asString()).isEqualTo("pagos@clinica.bo");
        assertThat(body.path("paymentNumber").asString()).isEqualTo("ERP-QR-synthetic");
        assertThat(body.path("clientCode").asString()).isEqualTo("patient-code");
        assertThat(body.path("callbackUrl").asString()).isEqualTo("https://example.invalid/callback");
        assertThat(body.path("currency").asInt()).isEqualTo(2);
        for (JsonNode amount : List.of(body.path("amount"), body.at("/orderDetail/0/price"), body.at("/orderDetail/0/total")))
            assertThat(amount.decimalValue()).isEqualByComparingTo("0.35");
        assertThat(body.at("/orderDetail/0/quantity").asInt()).isEqualTo(1);
        assertThat(body.at("/orderDetail/0/serial").asInt()).isEqualTo(1);
        assertThat(body.at("/orderDetail/0/discount").asInt()).isZero();
        assertThat(body.at("/orderDetail/0/product").asString()).isEqualTo("Cuota odontológica");
        var paid = adapter.consultarTransaccion("provider-123");
        assertThat(paid.pagado()).isTrue();
        assertThat(paid.amount()).isEqualByComparingTo("0.35");
        assertThat(bodies.getLast()).isEqualTo(json.readTree("{\"pagofacilTransactionId\":\"provider-123\",\"companyTransactionId\":\"provider-123\"}"));
        adapter.generar(request);
        assertThat(paths).containsExactly("/login", "/list-enabled-services", "/generate-qr", "/query-transaction", "/generate-qr");
        assertThat(bodies.getFirst().isEmpty()).isTrue();
    }
    @Test void overrideMinimumFloorHalfUpAndReferenceFallback() {
        generated = "{\"qrBase64\":\"" + PNG + "\"}";
        var adapter = adapter(7, Clock.systemUTC());
        for (String[] amounts : List.of(new String[]{"0.01", "0.01"}, new String[]{"345.00", "0.35"}, new String[]{"9999999999.99", "10000000.00"})) {
            assertThat(adapter.generar(request(amounts[0])).reference()).isEqualTo("ERP-QR-synthetic");
            assertThat(bodies.getLast().path("amount").decimalValue()).isEqualByComparingTo(amounts[1]);
            assertThat(bodies.getLast().path("paymentMethod").asInt()).isEqualTo(7);
        }
        assertThat(paths).doesNotContain("/list-enabled-services");
    }
    @Test void localOrEmptyCallbackUsesProviderPlaceholder() {
        for (String callback : List.of("", "  ", "http://localhost:8080/callback", "http://127.0.0.1:8080/callback")) {
            adapter(4, Clock.systemUTC(), callback).generar(request("350"));
            assertThat(bodies.getLast().path("callbackUrl").asString())
                    .isEqualTo("https://masterqr.pagofacil.com.bo/callback/placeholder");
        }
        adapter(4, Clock.systemUTC(), "https://localhost.example.invalid/callback").generar(request("350"));
        assertThat(bodies.getLast().path("callbackUrl").asString()).isEqualTo("https://localhost.example.invalid/callback");
    }
    @Test void unavailableOrEmptyServicesUseAndCacheMethod34() {
        for (int status : List.of(200, 503)) {
            paths.clear();
            services = "[]";
            servicesStatus = status;
            var adapter = adapter(-1, Clock.systemUTC());
            assertThat(adapter.generar(request("350")).reference()).isEqualTo("provider-123");
            assertThat(bodies.getLast().path("paymentMethod").asInt()).isEqualTo(34);
            adapter.generar(request("350"));
            assertThat(paths).containsExactly("/login", "/list-enabled-services", "/generate-qr", "/generate-qr");
        }
    }
    @Test void queryAddsSecretAndNumericLegacyReferenceAndReadsJsonStringValues() {
        queried = json.writeValueAsString(queried);
        var adapter = adapter(4, Clock.systemUTC());
        var paid = adapter.consultarTransaccion("12345678901234567890");
        assertThat(paid.pagado()).isTrue();
        assertThat(paid.amount()).isEqualByComparingTo("0.35");
        assertThat(bodies.getLast()).isEqualTo(json.readTree("{\"pagofacilTransactionId\":\"12345678901234567890\",\"companyTransactionId\":\"12345678901234567890\",\"tnTransaccionPF\":12345678901234567890}"));
        adapter.consultarTransaccion("provider-123");
        assertThat(bodies.getLast()).isEqualTo(json.readTree("{\"pagofacilTransactionId\":\"provider-123\",\"companyTransactionId\":\"provider-123\"}"));
        assertThat(querySecrets).containsExactly("synthetic-secret", "synthetic-secret");
        queried = "\"not-json\"";
        assertThatThrownBy(() -> adapter.consultarTransaccion("123")).hasMessageContaining("PagoFácil");
    }
    @Test void tokenRenewsFiveMinutesBeforeExpiry() {
        class MutableClock extends Clock {
            Instant instant = Instant.parse("2026-11-09T00:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return instant; }
        }
        var clock = new MutableClock();
        login = "{\"accessToken\":\"synthetic-bearer\",\"expiresInMinutes\":10}";
        var adapter = adapter(4, clock);
        adapter.generar(request("350"));
        clock.instant = clock.instant.plusSeconds(299);
        adapter.generar(request("350"));
        clock.instant = clock.instant.plusSeconds(1);
        adapter.generar(request("350"));
        assertThat(paths.stream().filter(p -> p.equals("/login")).count()).isEqualTo(2);
    }
    @Test void protocolErrorsNeverFabricateQrOrLeakCredentials() {
        var adapter = adapter(4, Clock.systemUTC());
        error = 1;
        assertThatThrownBy(() -> adapter.generar(request("350"))).hasMessageContaining("PagoFácil").hasMessageNotContaining("synthetic-secret");
        error = 0; login = "{}";
        assertThatThrownBy(() -> adapter.generar(request("350"))).hasMessageContaining("PagoFácil");
        login = "{\"accessToken\":\"synthetic-bearer\",\"expiresInMinutes\":10}";
        httpStatus = 503;
        assertThatThrownBy(() -> adapter.generar(request("350"))).hasMessageContaining("PagoFácil");
        assertThat(paths).containsOnly("/login");
    }
    @Test void malformedQrFailsWithoutFallbackMock() {
        generated = "{\"transactionId\":\"provider-123\",\"qrBase64\":\"not-base64\"}";
        assertThatThrownBy(() -> adapter(4, Clock.systemUTC()).generar(request("350"))).hasMessageContaining("PagoFácil");
    }
}
