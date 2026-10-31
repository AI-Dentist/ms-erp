package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.JSON;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static org.assertj.core.api.Assertions.assertThat;

/** T3 / S1-S2: default production filter chain, loopback OIDC, in-memory signing keys, embedded PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OdontologoIdentitySecurityTest {

    private static final String DATABASE = "erp_identity_http";
    private static final EmbeddedOidc OIDC = EmbeddedOidc.started();
    private static final String SELF = "{ obtenerMiOdontologoId }";
    private static final String STATE = """
            { listarUsuarios { id username email nombreCompleto activo rol { codigo } }
              listarOdontologos(soloActivos: false) { id ci matriculaProfesional nombres apellidos telefono email porcentajeComision activo especialidades { id } }
              listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt } }
            """;

    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        // Database bootstrap only; all fixture data and assertions below cross public HTTP GraphQL.
        try (Connection c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             var s = c.createStatement()) {
            if (!s.executeQuery("SELECT 1 FROM pg_database WHERE datname='" + DATABASE + "'").next()) {
                s.execute("CREATE DATABASE " + DATABASE);
            }
        }
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("spring.rabbitmq.listener.direct.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.security.dev-mode", () -> false);
        r.add("erp.security.local-dev-user.enabled", () -> false);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> OIDC.issuer);
    }

    @AfterAll
    static void close() {
        OIDC.close();
    }

    private record Identity(String user, String doctor, String subject, String username) { }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private String admin() {
        return OIDC.token("synthetic-admin-subject", "admin", "ADMIN");
    }

    private JsonNode success(String query, String token) throws Exception {
        var response = graphql(port, query, bearer(token));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.has("errors")).as(response.body()).isFalse();
        assertThat(body.path("data").isObject()).as(response.body()).isTrue();
        return body.path("data");
    }

    private Identity fixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 18);
        String username = "identity-http-" + suffix;
        String user = success("""
                mutation { crearUsuario(input: {username: "%s", email: "%s@example.test",
                  nombreCompleto: "Synthetic %s", password: "Synthetic2026!", rolCodigo: "ODONTOLOGO"}) { id } }
                """.formatted(username, username, suffix), admin()).path("crearUsuario").path("id").asText();
        String doctor = success("""
                mutation { crearOdontologo(input: {ci: "CI-%s", matriculaProfesional: "MAT-%s",
                  nombres: "Synthetic %s", apellidos: "HTTP", telefono: "+59170000000",
                  email: "%s@example.test", porcentajeComision: 30, especialidadesIds: []}) { id } }
                """.formatted(suffix, suffix, suffix, username), admin()).path("crearOdontologo").path("id").asText();
        assertThat(UUID.fromString(user)).isNotNull();
        assertThat(UUID.fromString(doctor)).isNotNull();
        return new Identity(user, doctor, UUID.randomUUID().toString(), username);
    }

    private String binding(Identity i) {
        return """
                mutation { vincularIdentidadOdontologo(input: {usuarioId: "%s", odontologoId: "%s",
                  keycloakSubject: "%s"}) }
                """.formatted(i.user(), i.doctor(), i.subject());
    }

    private void bind(Identity i) throws Exception {
        assertThat(success(binding(i), admin()).path("vincularIdentidadOdontologo").asText()).isEqualTo(i.doctor());
    }

    private JsonNode snapshot() throws Exception {
        return success(STATE, admin());
    }

    private void denied(String query, String token, String classification, String message) throws Exception {
        var response = graphql(port, query, bearer(token));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("data").isNull()).as(response.body()).isTrue();
        JsonNode errors = body.path("errors");
        assertThat(errors.size()).as(response.body()).isEqualTo(1);
        assertThat(errors.get(0).path("extensions").path("classification").asText()).isEqualTo(classification);
        if (message != null) {
            assertThat(errors.get(0).path("message").asText()).isEqualTo(message);
        }
    }

    private void self(Identity i, String role, String username) throws Exception {
        assertThat(success(SELF, OIDC.token(i.subject(), username, role)).path("obtenerMiOdontologoId").asText())
                .isEqualTo(i.doctor());
    }

    @Test
    void verifiedSubjectDifferentFromPrimaryKeyResolvesForDoctorAndAdminNotPreferredUsername() throws Exception {
        Identity a = fixture();
        Identity b = fixture();
        assertThat(a.subject()).isNotEqualTo(a.doctor()).isNotEqualTo(a.user());
        bind(a);
        bind(b);
        JsonNode before = snapshot();
        self(a, "ODONTOLOGO", b.username());
        self(a, "ADMIN", b.username());
        self(b, "ODONTOLOGO", a.username());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void anonymousAndForgedBearerCannotLookupOrProvisionEvenWithDevUserHeader() throws Exception {
        Identity i = fixture();
        // Both queries must reach implemented behavior for an authenticated administrator first.
        bind(i);
        self(i, "ODONTOLOGO", i.username());
        JsonNode before = snapshot();
        String forged = OIDC.token(EmbeddedOidc.newKey(), OIDC.issuer, i.subject(), i.username(), "ADMIN",
                Instant.now().plusSeconds(300));
        for (String query : List.of(SELF, binding(i))) {
            for (Map<String, String> headers : List.of(Map.of("X-Dev-User", "admin"),
                    Map.of("Authorization", "Bearer " + forged, "X-Dev-User", "admin"))) {
                var response = graphql(port, query, headers);
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.body()).isEmpty();
                assertThat(snapshot()).isEqualTo(before);
                self(i, "ODONTOLOGO", i.username());
            }
        }
    }

    @Test
    void signedTokenWithoutSubjectCannotFallBackToBoundPreferredUsername() throws Exception {
        Identity i = fixture();
        bind(i);
        JsonNode before = snapshot();
        for (String role : List.of("ODONTOLOGO", "ADMIN")) {
            denied(SELF, OIDC.token(null, i.username(), role), "FORBIDDEN", "Se requiere un JWT con subject");
            assertThat(snapshot()).isEqualTo(before);
            self(i, "ODONTOLOGO", i.username());
        }
    }

    @Test
    void verifiedButUnboundSubjectCannotUseMatchingPreferredUsername() throws Exception {
        Identity i = fixture();
        bind(i);
        JsonNode before = snapshot();
        denied(SELF, OIDC.token(UUID.randomUUID().toString(), i.username(), "ODONTOLOGO"),
                "NOT_FOUND", "Identidad odontológica no disponible");
        assertThat(snapshot()).isEqualTo(before);
        self(i, "ODONTOLOGO", i.username());
    }

    @Test
    void realJwtRolesEnforceSelfAndProvisioningBoundariesWithoutMutation() throws Exception {
        Identity i = fixture();
        JsonNode unbound = snapshot();
        for (String role : List.of("ODONTOLOGO", "CAJERO", "RECEPCIONISTA", "UNRECOGNIZED")) {
            denied(binding(i), OIDC.token(i.subject(), i.username(), role), "FORBIDDEN", null);
            assertThat(snapshot()).isEqualTo(unbound);
        }
        bind(i);
        JsonNode bound = snapshot();
        for (String role : List.of("CAJERO", "RECEPCIONISTA", "UNRECOGNIZED")) {
            denied(SELF, OIDC.token(i.subject(), i.username(), role), "FORBIDDEN", null);
            assertThat(snapshot()).isEqualTo(bound);
            self(i, "ODONTOLOGO", i.username());
        }
        // Existing public read permission remains usable through the unchanged realm-role converter.
        assertThat(success("{ listarRoles { codigo } }", OIDC.token(i.subject(), i.username(), "CAJERO"))
                .path("listarRoles").size()).isGreaterThan(0);
        assertThat(snapshot()).isEqualTo(bound);
    }
}
