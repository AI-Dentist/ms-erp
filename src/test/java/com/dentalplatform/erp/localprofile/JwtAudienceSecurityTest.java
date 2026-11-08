package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.JSON;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static org.assertj.core.api.Assertions.assertThat;

/** Shared public HTTP matrix, inherited by both real security chains. No mocked decoder or storage assertions. */
abstract class JwtAudienceSecurityTest {
    static final String EXPECTED = "erp-audience-test";
    private static final String STATE = """
            { listarEspecialidades { id codigo nombre descripcion activo }
              listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt } }
            """;

    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    abstract EmbeddedOidc oidc();
    abstract boolean local();

    static void properties(DynamicPropertyRegistry r, String database, EmbeddedOidc oidc, boolean local)
            throws Exception {
        // Reuse the existing embedded PostgreSQL bootstrap; domain fixtures only use public GraphQL.
        try (Connection c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             var s = c.createStatement()) {
            if (!s.executeQuery("SELECT 1 FROM pg_database WHERE datname='" + database + "'").next()) {
                s.execute("CREATE DATABASE " + database);
            }
        }
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(database));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("spring.rabbitmq.listener.direct.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.security.dev-mode", () -> false);
        r.add("erp.security.local-dev-user.enabled", () -> local);
        r.add("erp.security.jwt.audience", () -> EXPECTED);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> oidc.issuer);
        // Preserve the existing explicit issuer + JWKS Boot configuration path, not just discovery.
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.issuer + "/jwks");
        r.add("erp.security.local-dev-user.jwt-issuer-uri", () -> oidc.issuer);
    }

    private String username(String role) {
        if (local()) return role.equals("ADMIN") ? "admin-scz" : "caja-scz";
        return role.equals("ADMIN") ? "admin" : "cajero";
    }

    private Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private String token(String rawAudience, String role) throws Exception {
        return token(rawAudience, role, oidc().key, oidc().issuer, Instant.now().plusSeconds(300));
    }

    private String token(String rawAudience, String role, RSAKey key, String issuer, Instant expiry)
            throws Exception {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", issuer);
        claims.put("sub", "audience-" + username(role));
        claims.put("preferred_username", username(role));
        claims.put("realm_access", Map.of("roles", List.of(role)));
        claims.put("iat", Instant.now().minusSeconds(600).getEpochSecond());
        claims.put("exp", expiry.getEpochSecond());
        String payload = JSON.writeValueAsString(claims);
        if (rawAudience != null) {
            payload = payload.substring(0, payload.length() - 1) + ",\"aud\":" + rawAudience + "}";
        }
        // JWTClaimsSet normalizes aud; sign raw JSON so malformed claim types really reach the decoder.
        var jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(oidc().key.getKeyID()).build(), new Payload(payload));
        jws.sign(new RSASSASigner(key));
        String signed = jws.serialize();
        JsonNode wire = JSON.readTree(JWSObject.parse(signed).getPayload().toString());
        if (rawAudience == null) {
            assertThat(wire.has("aud")).isFalse();
        } else {
            assertThat(wire.get("aud")).isEqualTo(JSON.readTree(rawAudience));
        }
        return signed;
    }

    private JsonNode success(String query, String token) throws Exception {
        var response = graphql(port, query, bearer(token));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.has("errors")).as(response.body()).isFalse();
        assertThat(body.path("data").isObject()).as(response.body()).isTrue();
        return body.path("data");
    }

    private JsonNode snapshot() throws Exception {
        return success(STATE, token("\"" + EXPECTED + "\"", "ADMIN"));
    }

    private String mutation() {
        return "mutation { crearEspecialidad(codigo: \"AUD-" + UUID.randomUUID().toString().substring(0, 8)
                + "\", nombre: \"Synthetic audience\") { id codigo } }";
    }

    private void rejected(String token) throws Exception {
        JsonNode before = snapshot();
        Map<String, String> headers = local()
                ? Map.of("Authorization", "Bearer " + token, "X-Dev-User", "admin-scz") : bearer(token);
        var response = graphql(port, mutation(), headers);
        // Assert readback even on RED, so unexpected acceptance cannot hide a changed aggregate/audit trail.
        JsonNode after = snapshot();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(response.statusCode()).isEqualTo(401),
                () -> assertThat(response.body()).isEmpty(),
                () -> assertThat(after).isEqualTo(before));
    }

    static Stream<Arguments> rejectedAudiences() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("null", "null"),
                Arguments.of("empty string", "\"\""),
                Arguments.of("default is not the configured audience", "\"ms-erp\""),
                Arguments.of("other service", "\"ms2-clinico-ia\""),
                Arguments.of("case differs", "\"ERP-AUDIENCE-TEST\""),
                Arguments.of("substring only", "\"prefix-erp-audience-test-suffix\""),
                Arguments.of("padded", "\" erp-audience-test \""),
                Arguments.of("empty list", "[]"),
                Arguments.of("wrong list", "[\"ms-erp\",\"ms3-agenda-stock\"]"),
                Arguments.of("number", "42"),
                Arguments.of("boolean", "true"),
                Arguments.of("object", "{\"resource\":\"erp-audience-test\"}"),
                Arguments.of("expected plus number", "[\"erp-audience-test\",42]"),
                Arguments.of("expected plus null", "[\"erp-audience-test\",null]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedAudiences")
    void invalidAudienceIs401WithoutMutationOrAuditAndNeverFallsBack(String label, String rawAudience)
            throws Exception {
        rejected(token(rawAudience, "ADMIN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"erp-audience-test\"", "[\"ms2-clinico-ia\",\"erp-audience-test\",\"ms3-agenda-stock\"]"})
    void exactStringAndMultiAudienceAllowAdminMutationWithPublicReadbackAndAudit(String rawAudience)
            throws Exception {
        JsonNode before = snapshot();
        JsonNode created = success(mutation(), token(rawAudience, "ADMIN")).path("crearEspecialidad");
        String id = created.path("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();
        JsonNode after = snapshot();
        assertThat(after.path("listarEspecialidades").size())
                .isEqualTo(before.path("listarEspecialidades").size() + 1);
        assertThat(after.path("listarEspecialidades").toString()).contains(id, created.path("codigo").asText());
        assertThat(after.path("listarAuditLogs").size()).isEqualTo(before.path("listarAuditLogs").size() + 1);
        assertThat(after.path("listarAuditLogs").toString()).contains(id);
    }

    @Test
    void legitimateCashierRoleStillReadsButCannotMutateWithExpectedAudience() throws Exception {
        String cashier = token("\"" + EXPECTED + "\"", "CAJERO");
        assertThat(success("{ listarRoles { codigo } }", cashier).path("listarRoles").size()).isPositive();
        JsonNode before = snapshot();
        var denied = graphql(port, mutation(), bearer(cashier));
        assertThat(denied.statusCode()).isEqualTo(200);
        assertThat(denied.body()).contains("FORBIDDEN").doesNotContain("Synthetic audience");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "signature", "expiry"})
    void expectedAudienceDoesNotBypassExistingJwtValidation(String invalid) throws Exception {
        rejected(token("\"" + EXPECTED + "\"", "ADMIN",
                invalid.equals("signature") ? EmbeddedOidc.newKey() : oidc().key,
                invalid.equals("issuer") ? oidc().issuer + "/wrong" : oidc().issuer,
                invalid.equals("expiry") ? Instant.now().minusSeconds(120) : Instant.now().plusSeconds(300)));
    }
}
