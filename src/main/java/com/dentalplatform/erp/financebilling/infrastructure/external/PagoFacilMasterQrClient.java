package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort.*;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort.escalarMontoPagoFacil;

/** Credential-bearing protocol stays inside this adapter; no response body or secret reaches logs/errors. */
final class PagoFacilMasterQrClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base, service, secret, callback;
    private final Clock clock;
    private String token;
    private Instant renewAt = Instant.MIN;
    private int method;

    PagoFacilMasterQrClient(String base, String service, String secret, int method, String callback, Clock clock) {
        this.base = base.replaceAll("/+$", ""); this.service = service; this.secret = secret;
        this.method = method; this.callback = callback; this.clock = clock;
    }

    QrGenerado generar(SolicitudQr request) {
        BigDecimal amount = escalarMontoPagoFacil(request.montoOriginalBs());
        var body = new LinkedHashMap<String,Object>();
        body.put("paymentMethod", metodo()); body.put("clientName", request.nombre());
        body.put("documentType", 1); body.put("documentId", request.ciNit());
        body.put("phoneNumber", "70000000"); body.put("email", "pagos@clinica.bo");
        body.put("paymentNumber", request.companyTransactionId()); body.put("amount", amount);
        body.put("currency", 2); body.put("clientCode", request.clientCode()); body.put("callbackUrl", callback);
        body.put("orderDetail", List.of(Map.of("serial", 1, "product", request.concepto(), "quantity", 1,
                "price", amount, "discount", 0, "total", amount)));
        var values = post("/generate-qr", body, bearer());
        String reference = values.path("transactionId").asString("");
        if (reference.isBlank()) { reference = request.companyTransactionId(); }
        if (reference.length() > 100 || reference.startsWith("MOCK-")) { throw failure(); }
        String image = values.path("qrBase64").asString("").replaceFirst("^data:image/[^;]+;base64,", "");
        try {
            byte[] bytes = Base64.getDecoder().decode(image);
            if (bytes.length < 8 || !Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) { throw failure(); }
            String expiration = values.path("expirationDate").asString("");
            OffsetDateTime expires = null;
            if (!expiration.isBlank()) {
                try { expires = OffsetDateTime.parse(expiration); }
                catch (java.time.format.DateTimeParseException e) {
                    expires = LocalDateTime.parse(expiration, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .atZone(ZoneId.of("America/La_Paz")).toOffsetDateTime();
                }
                if (!expires.toInstant().isAfter(clock.instant())) { throw failure(); }
            }
            return new QrGenerado(reference, image, expires);
        } catch (IllegalArgumentException | java.time.DateTimeException e) { throw failure(); }
    }

    ConsultaTransaccion consultar(String reference) {
        var values = post("/query-transaction", Map.of("pagofacilTransactionId", reference, "companyTransactionId", reference), bearer());
        String status = values.path("paymentStatus").asString(values.path("status").asString(""));
        boolean paid = Set.of("2", "5", "PAGADO", "COMPLETED").contains(status.toUpperCase(Locale.ROOT));
        try {
            BigDecimal amount = new BigDecimal(values.path("amount").asString(""));
            Integer currency = values.has("currencyId") ? values.path("currencyId").asInt(-1) : null;
            return new ConsultaTransaccion(paid, amount, currency, values.path("pagofacilTransactionId").asString(""),
                    values.path("companyTransactionId").asString(""));
        } catch (NumberFormatException e) { throw failure(); }
    }

    private synchronized String bearer() {
        if (token == null || !clock.instant().isBefore(renewAt)) {
            var values = post("/login", Map.of(), Map.of("tcTokenService", service, "tcTokenSecret", secret));
            String fresh = values.path("accessToken").asString("");
            try {
                BigDecimal minutes = new BigDecimal(values.path("expiresInMinutes").asString(""));
                if (fresh.isBlank() || minutes.signum() <= 0) { throw failure(); }
                long seconds = minutes.subtract(BigDecimal.valueOf(5)).max(BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(60)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
                renewAt = clock.instant().plusSeconds(seconds);
                token = fresh;
            } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException e) { throw failure(); }
        }
        return "Bearer " + token;
    }

    private synchronized int metodo() {
        if (method > 0) { return method; }
        var values = post("/list-enabled-services", Map.of(), bearer());
        if (!values.isArray() || values.isEmpty()) { throw failure(); }
        JsonNode selected = values.get(0);
        for (JsonNode entry : values) {
            if ("BOB".equalsIgnoreCase(entry.path("currencyName").asString(""))
                    && entry.path("enabled").asBoolean(true)
                    && entry.path("paymentMethodName").asString("QR").toUpperCase(Locale.ROOT).contains("QR")) {
                selected = entry; break;
            }
        }
        int found = selected.path("paymentMethodId").asInt(0);
        if (found <= 0) { throw failure(); }
        method = found;
        return found;
    }

    private JsonNode post(String path, Object body, String authorization) {
        return post(path, body, Map.of("Authorization", authorization));
    }
    private JsonNode post(String path, Object body, Map<String,String> headers) {
        try {
            URI uri = URI.create(base + path);
            // Permit HTTP only for deterministic loopback tests/local stubs. Never forward credentials on redirects.
            if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()))) { throw failure(); }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) { throw failure(); }
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Response-Language", "es")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            headers.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) { throw failure(); }
            var envelope = json.readTree(response.body());
            if (!envelope.path("error").isIntegralNumber() || envelope.path("error").asInt(-1) != 0
                    || !envelope.hasNonNull("values")) { throw failure(); }
            return envelope.path("values");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (java.io.IOException | IllegalArgumentException | tools.jackson.core.JacksonException e) { throw failure(); }
    }
    private BusinessRuleException failure() { return new BusinessRuleException("PagoFácil: respuesta o conexión del proveedor inválida"); }
}
