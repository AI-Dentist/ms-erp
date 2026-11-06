package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Public HTTP through local's real resource-server chain; every identity is synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OfflineActivationHttpIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final RSAKey IDP = key();
    private static final RSAKey SIGNER = key();
    private static final HttpServer OIDC = oidc();
    private static final String ISSUER = "http://127.0.0.1:" + OIDC.getAddress().getPort() + "/realms/offline-test";
    private static final String GRANT_ISSUER = "erp-offline-test";
    private static final String KID = "offline-test-key";
    private static final java.util.concurrent.atomic.AtomicReference<String> SIGNING_KEY = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<String> SIGNING_KID = new java.util.concurrent.atomic.AtomicReference<>(KID);
    private static final java.util.concurrent.atomic.AtomicReference<String> SIGNING_ISSUER = new java.util.concurrent.atomic.AtomicReference<>(GRANT_ISSUER);
    private static final String STATE = """
            { listarUsuarios { id username email nombreCompleto activo rol { id codigo nombre permisos } }
              listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt }
              listarPacientes { id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion activo }
              listarOdontologos(soloActivos: false) { id ci matriculaProfesional nombres apellidos telefono email porcentajeComision activo especialidades { id } }
              listarEspecialidades { id codigo nombre descripcion activo }
              listarConsultorios { id codigo nombre ubicacion estado }
              listarServiciosOdontologicos { id codigo nombre descripcion precioBaseBs activo especialidad { id } }
              listarOrdenesCobro { id modalidadPago montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs cantidadCuotas estado createdAt
                paciente { id } odontologo { id } consultaExternaId
                items { id servicio { id } piezaDental cantidad precioUnitarioBs subtotalBs }
                cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado fechaPago } }
              obtenerFlujoCaja(fechaInicio: "1970-01-01T00:00:00Z", fechaFin: "9999-01-01T00:00:00Z") {
                totalIngresosBs totalEgresosBs saldoNetoBs movimientos { id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento
                  recibo { id numeroRecibo ciNitCliente razonSocialCliente concepto montoTotalBs pdfUrlS3 enviadoWhatsapp fechaEmision } } } }
            """;
    private static final String ACTIVATION = """
            mutation($input: ActivarJornadaOfflineInput!) {
              activarJornadaOffline(input: $input) { credencial clavePublicaSpki }
            }
            """;
    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String db = "erp_offline_activation";
        try (Connection c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             var s = c.createStatement()) {
            if (!s.executeQuery("SELECT 1 FROM pg_database WHERE datname='" + db + "'").next()) {
                s.execute("CREATE DATABASE " + db);
            }
        }
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(db));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("spring.rabbitmq.listener.direct.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.billing-saga.outbox.enabled", () -> false);
        r.add("erp.security.local-dev-user.jwt-issuer-uri", () -> ISSUER);
        String privateKey = base64(SIGNER.toPrivateKey().getEncoded());
        SIGNING_KEY.set(privateKey);
        r.add("erp.offline.signing.private-key-pkcs8-base64", SIGNING_KEY::get);
        r.add("erp.offline.signing.key-id", SIGNING_KID::get);
        r.add("erp.offline.signing.grant-issuer", SIGNING_ISSUER::get);
    }

    @AfterAll static void close() { OIDC.stop(0); }

    @Test
    void signedGrantsUseCanonicalIdentityAndConservativeRoleCapabilities() throws Exception {
        for (var role : Map.of("ADMIN", Set.of("CASH", "EXPENSES", "MANUAL_ORDERS", "PATIENTS"),
                "CAJERO", Set.of("CASH", "EXPENSES", "MANUAL_ORDERS"),
                "RECEPCIONISTA", Set.of("MANUAL_ORDERS", "PATIENTS")).entrySet()) {
            Identity user = fixture(role.getKey(), true);
            UUID workspace = UUID.randomUUID(), device = UUID.randomUUID();
            JsonNode before = snapshot();
            Instant started = Instant.now();
            JsonNode response = success(ACTIVATION, input(workspace, device, null), token(user.subject(), "different-username", role.getKey()));
            JsonNode result = response.path("activarJornadaOffline");
            JWSObject grant = JWSObject.parse(result.path("credencial").asText());
            assertThat(grant.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
            assertThat(grant.getHeader().getType().toString()).isEqualTo("erp-offline-grant+jws");
            assertThat(grant.getHeader().getKeyID()).isEqualTo(KID);
            byte[] spki = Base64.getDecoder().decode(result.path("clavePublicaSpki").asText());
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
            assertThat(publicKey.getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
            assertThat(publicKey.getEncoded()).isEqualTo(SIGNER.toRSAPublicKey().getEncoded());
            assertThat(grant.verify(new RSASSAVerifier(publicKey))).isTrue();
            assertThat(grant.verify(new RSASSAVerifier(IDP.toRSAPublicKey()))).isFalse();
            JsonNode payload = JSON.readTree(grant.getPayload().toString());
            assertThat(payload.path("version").asInt()).isEqualTo(1);
            assertThat(UUID.fromString(payload.path("grantId").asText())).isNotNull();
            assertThat(payload.path("grantIssuer").asText()).isEqualTo(GRANT_ISSUER);
            assertThat(payload.path("audience").asText()).isEqualTo("erp-offline-local-only");
            assertThat(payload.path("erpUserId").asText()).isEqualTo(user.id());
            assertThat(payload.path("identityIssuer").asText()).isEqualTo(ISSUER);
            assertThat(payload.path("identitySubject").asText()).isEqualTo(user.subject());
            assertThat(payload.path("role").asText()).isEqualTo(role.getKey());
            assertThat(payload.path("workspaceId").asText()).isEqualTo(workspace.toString());
            assertThat(payload.path("deviceId").asText()).isEqualTo(device.toString());
            assertThat(strings(payload.path("capabilities"))).isEqualTo(role.getValue());
            Instant issued = Instant.parse(payload.path("issuedAt").asText());
            Instant expires = Instant.parse(payload.path("expiresAt").asText());
            assertThat(issued).isBetween(started.minusSeconds(2), Instant.now().plusSeconds(2));
            assertThat(expires).isEqualTo(issued.plusSeconds(28800));
            assertThat(payload.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("version", "grantId", "grantIssuer", "audience", "erpUserId",
                            "identityIssuer", "identitySubject", "role", "capabilities", "workspaceId", "deviceId", "issuedAt", "expiresAt");
            String[] parts = grant.serialize().split("\\.");
            String modified = base64Url(grant.getPayload().toString().replace(workspace.toString(), UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
            assertThat(JWSObject.parse(parts[0] + "." + modified + "." + parts[2]).verify(new RSASSAVerifier(publicKey))).isFalse();
            JsonNode again = success(ACTIVATION, input(workspace, device, null), token(user.subject(), user.username(), role.getKey()));
            assertThat(JSON.readTree(JWSObject.parse(again.path("activarJornadaOffline").path("credencial").asText())
                    .getPayload().toString()).path("grantId").asText()).isNotEqualTo(payload.path("grantId").asText());
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void durationBoundariesAndInvalidInputsDoNotWrite() throws Exception {
        Identity user = fixture("ADMIN", true);
        String bearer = token(user.subject(), user.username(), "ADMIN");
        UUID workspace = UUID.randomUUID(), device = UUID.randomUUID();
        JsonNode before = snapshot();
        for (int seconds : List.of(1, 28800)) {
            JsonNode result = success(ACTIVATION, input(workspace, device, seconds), bearer).path("activarJornadaOffline");
            JsonNode payload = JSON.readTree(JWSObject.parse(result.path("credencial").asText()).getPayload().toString());
            assertThat(Instant.parse(payload.path("expiresAt").asText()))
                    .isEqualTo(Instant.parse(payload.path("issuedAt").asText()).plusSeconds(seconds));
        }
        for (int seconds : List.of(0, -1, 28801)) {
            assertError(response(ACTIVATION, input(workspace, device, seconds), bearer), "BAD_REQUEST");
        }
        var extra = new java.util.HashMap<>(input(workspace, device, null));
        extra.put("pin", "1234");
        JsonNode invalidInput = response(ACTIVATION, extra, bearer);
        assertThat(invalidInput.path("errors").get(0).path("message").asText()).contains("pin");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void inactiveUnlinkedMismatchedUnsupportedAndNonJwtAreDeniedWithoutWrites() throws Exception {
        Identity active = fixture("CAJERO", true);
        Identity inactive = fixture("RECEPCIONISTA", true);
        Identity unsupported = fixture("ODONTOLOGO", true);
        Identity unlinked = fixture("ADMIN", false);
        success("mutation { actualizarUsuario(id: \"" + inactive.id() + "\", input: {rolCodigo: \"RECEPCIONISTA\", activo: false}) { id } }", Map.of(), admin());
        JsonNode before = snapshot();
        var args = input(UUID.randomUUID(), UUID.randomUUID(), null);
        assertError(response(ACTIVATION, args, token(active.subject(), active.username(), "ADMIN")), "FORBIDDEN");
        assertError(response(ACTIVATION, args, token(inactive.subject(), inactive.username(), "RECEPCIONISTA")), "NOT_FOUND");
        assertError(response(ACTIVATION, args, token(unsupported.subject(), unsupported.username(), "ODONTOLOGO")), "FORBIDDEN");
        assertError(response(ACTIVATION, args, token(unlinked.subject(), unlinked.username(), "ADMIN")), "NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
        var nonJwt = send(ACTIVATION, args, Map.of("X-Dev-User", "admin-scz"));
        assertThat(nonJwt.statusCode()).isEqualTo(200);
        assertError(JSON.readTree(nonJwt.body()), "FORBIDDEN");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void grantNeverAuthenticatesGraphqlEvenWithReplayKeyAndDevFallback() throws Exception {
        Identity user = fixture("ADMIN", true);
        String bearer = token(user.subject(), user.username(), "ADMIN");
        String credential = success(ACTIVATION, input(UUID.randomUUID(), UUID.randomUUID(), null), bearer)
                .path("activarJornadaOffline").path("credencial").asText();
        String mutation = "mutation { crearEspecialidad(codigo: \"OFF-" + UUID.randomUUID().toString().substring(0, 8)
                + "\", nombre: \"Synthetic Offline\") { id } }";
        String key = "offline-" + UUID.randomUUID();
        // Populate the existing idempotency cache with a legitimate request first.
        var legitimate = send(mutation, Map.of(), Map.of("Authorization", "Bearer " + bearer, "Idempotency-Key", key));
        assertThat(legitimate.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(legitimate.body()).has("errors")).isFalse();
        var replay = send(mutation, Map.of(), Map.of("Authorization", "Bearer " + bearer, "Idempotency-Key", key));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(replay.body()).isEqualTo(legitimate.body());
        JsonNode before = snapshot();
        for (String replayKey : List.of(key, "offline-" + UUID.randomUUID())) {
            var denied = send(mutation, Map.of(), Map.of("Authorization", "Bearer " + credential,
                    "Idempotency-Key", replayKey, "X-Dev-User", "admin-scz"));
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.body()).isEmpty();
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void missingAndMalformedSigningConfigurationFailClosedAndRedactedAndLegacyIdentityStillWorks() throws Exception {
        Identity user = fixture("ADMIN", true);
        String bearer = token(user.subject(), user.username(), "ADMIN");
        assertThat(success("{ obtenerMiUsuarioId }", Map.of(), bearer).path("obtenerMiUsuarioId").asText()).isEqualTo(user.id());
        JsonNode before = snapshot();
        String valid = SIGNING_KEY.get();
        try {
            var weakGenerator = java.security.KeyPairGenerator.getInstance("RSA");
            weakGenerator.initialize(1024);
            String weak = base64(weakGenerator.generateKeyPair().getPrivate().getEncoded());
            for (String invalid : new String[] {null, "", "synthetic-invalid-private-key", weak}) {
                SIGNING_KEY.set(invalid);
                JsonNode result = response(ACTIVATION, input(UUID.randomUUID(), UUID.randomUUID(), null), bearer);
                assertError(result, "INTERNAL_ERROR");
                assertThat(result.path("errors").get(0).path("message").asText()).isEqualTo("Configuración de firma offline no disponible");
                assertThat(result.toString()).doesNotContain("synthetic-invalid-private-key", valid);
                assertThat(snapshot()).isEqualTo(before);
            }
            SIGNING_KEY.set(valid);
            for (var setting : List.of(SIGNING_KID, SIGNING_ISSUER)) {
                String original = setting.get();
                try {
                    setting.set("");
                    JsonNode result = response(ACTIVATION, input(UUID.randomUUID(), UUID.randomUUID(), null), bearer);
                    assertError(result, "INTERNAL_ERROR");
                    assertThat(result.path("errors").get(0).path("message").asText()).isEqualTo("Configuración de firma offline no disponible");
                    assertThat(result.toString()).doesNotContain(valid);
                    assertThat(snapshot()).isEqualTo(before);
                } finally { setting.set(original); }
            }
        } finally {
            SIGNING_KEY.set(valid);
        }
        assertThat(success("{ obtenerMiUsuarioId }", Map.of(), bearer).path("obtenerMiUsuarioId").asText()).isEqualTo(user.id());
    }

    private static void assertError(JsonNode body, String classification) {
        assertThat(body.path("data").isNull()).as(body.toString()).isTrue();
        assertThat(body.path("errors").size()).as(body.toString()).isEqualTo(1);
        assertThat(body.path("errors").get(0).path("extensions").path("classification").asText())
                .isEqualTo(classification);
    }

    private record Identity(String id, String username, String subject) { }
    private Identity fixture(String role, boolean bind) throws Exception {
        String username = "offline-" + UUID.randomUUID().toString().substring(0, 18);
        String subject = "offline-subject-" + UUID.randomUUID();
        String query = "mutation { crearUsuario(input: {username: \"" + username + "\", email: \"" + username
                + "@example.test\", nombreCompleto: \"Synthetic User\", password: \"Synthetic2026!\", rolCodigo: \""
                + role + "\"}) { id } }";
        String id = success(query, Map.of(), admin()).path("crearUsuario").path("id").asText();
        if (bind) success("mutation { vincularIdentidadUsuario(input: {usuarioId: \"" + id
                + "\", keycloakSubject: \"" + subject + "\"}) }", Map.of(), admin());
        return new Identity(id, username, subject);
    }
    private JsonNode snapshot() throws Exception { return success(STATE, Map.of(), admin()); }
    private String admin() { return token("fixture-admin", "admin-scz", "ADMIN"); }
    private static Map<String, Object> input(UUID workspace, UUID device, Integer duration) {
        var input = new java.util.HashMap<String, Object>();
        input.put("workspaceId", workspace.toString()); input.put("deviceId", device.toString());
        if (duration != null) input.put("durationSeconds", duration);
        return input;
    }
    private JsonNode success(String query, Map<String, Object> input, String bearer) throws Exception {
        JsonNode result = response(query, input, bearer);
        assertThat(result.has("errors")).as(result.toString()).isFalse();
        return result.path("data");
    }
    private JsonNode response(String query, Map<String, Object> input, String bearer) throws Exception {
        var http = send(query, input, Map.of("Authorization", "Bearer " + bearer));
        assertThat(http.statusCode()).isEqualTo(200);
        return JSON.readTree(http.body());
    }
    private HttpResponse<String> send(String query, Map<String, Object> input, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                Map.of("query", query, "variables", Map.of("input", input))))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Set<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).collect(Collectors.toSet());
    }
    private static String token(String subject, String username, String role) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("ms-erp")
                    .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(role)))
                    .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(IDP.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(IDP));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String base64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static String base64Url(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static HttpServer oidc() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/offline-test";
                String body = exchange.getRequestURI().getPath().endsWith("/jwks") ? new JWKSet(IDP.toPublicJWK()).toString()
                        : "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
