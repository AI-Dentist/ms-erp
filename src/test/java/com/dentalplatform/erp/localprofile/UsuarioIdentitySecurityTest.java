package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.JSON;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static org.assertj.core.api.Assertions.assertThat;

/** Public HTTP, default JWT security chain, synthetic data and loopback RS256 issuer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UsuarioIdentitySecurityTest {
    private static final EmbeddedOidc OIDC = EmbeddedOidc.started();
    private static final String DATABASE = "erp_user_identity_http";
    private static final String SELF = "{ obtenerMiUsuarioId }";
    private static final String UNAVAILABLE = "Identidad de usuario no disponible";
    private static final String CONFLICT = "La identidad de usuario ya está vinculada";
    private static final String STATE = """
            { listarUsuarios { id username email nombreCompleto activo rol { codigo } }
              listarOdontologos(soloActivos: false) { id ci activo }
              listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt } }
            """;
    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        // Only database bootstrap uses SQL. All fixture changes and state assertions use public HTTP.
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

    @AfterAll static void close() { OIDC.close(); }
    private record Identity(String user, String subject, String username) { }
    private String admin() { return OIDC.token("synthetic-admin", "admin", "ADMIN"); }

    private JsonNode response(String query, String token) throws Exception {
        var response = graphql(port, query, Map.of("Authorization", "Bearer " + token));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode success(String query, String token) throws Exception {
        var body = response(query, token);
        assertThat(body.has("errors")).as(body.toString()).isFalse();
        assertThat(body.path("data").isObject()).isTrue();
        return body.path("data");
    }

    private Identity fixture() throws Exception {
        String username = "user-http-" + UUID.randomUUID().toString().substring(0, 18);
        String id = success("""
                mutation { crearUsuario(input: {username: "%s", email: "%s@example.test",
                  nombreCompleto: "Synthetic User", password: "Synthetic2026!", rolCodigo: "RECEPCIONISTA"}) { id } }
                """.formatted(username, username), admin()).path("crearUsuario").path("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();
        return new Identity(id, "opaque:" + UUID.randomUUID(), username);
    }

    private String binding(String user, String subject) {
        return "mutation { vincularIdentidadUsuario(input: {usuarioId: \"" + user
                + "\", keycloakSubject: " + JSON.writeValueAsString(subject) + "}) }";
    }

    private void bind(Identity i) throws Exception {
        assertThat(success(binding(i.user(), i.subject()), admin()).path("vincularIdentidadUsuario").asText())
                .isEqualTo(i.user());
    }

    private JsonNode snapshot() throws Exception { return success(STATE, admin()); }

    private void self(Identity i, String role, String username) throws Exception {
        assertThat(success(SELF, OIDC.token(i.subject(), username, role)).path("obtenerMiUsuarioId").asText())
                .isEqualTo(i.user());
    }

    private void denied(String query, String token, String classification, String message) throws Exception {
        var body = response(query, token);
        assertThat(body.path("data").isNull()).as(body.toString()).isTrue();
        assertThat(body.path("errors").size()).isEqualTo(1);
        var error = body.path("errors").get(0);
        assertThat(error.path("extensions").path("classification").asText()).isEqualTo(classification);
        if (message != null) assertThat(error.path("message").asText()).isEqualTo(message);
    }

    private void rejected(String user, String subject, String classification, String message) throws Exception {
        var before = snapshot();
        denied(binding(user, subject), admin(), classification, message);
        assertThat(snapshot()).isEqualTo(before);
        denied(SELF, OIDC.token(subject, "admin", "ADMIN"), subject.isBlank() ? "FORBIDDEN" : "NOT_FOUND",
                subject.isBlank() ? "Se requiere un JWT con subject" : UNAVAILABLE);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void activeUserWithoutDoctorResolvesOnlyExactSubjectForCashierAndAdminWithoutRoleEquality() throws Exception {
        var a = fixture();
        var b = fixture();
        assertThat(a.subject()).isNotEqualTo(a.user());
        var before = snapshot();
        denied(SELF, OIDC.token(a.subject(), a.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        assertThat(snapshot()).isEqualTo(before);
        bind(a);
        bind(b);
        var bound = snapshot();
        assertThat(bound.path("listarUsuarios")).isEqualTo(before.path("listarUsuarios"));
        assertThat(bound.path("listarOdontologos")).isEqualTo(before.path("listarOdontologos"));
        assertThat(bound.path("listarAuditLogs").size()).isEqualTo(before.path("listarAuditLogs").size() + 2);
        for (String role : List.of("CAJERO", "ADMIN")) self(a, role, b.username());
        self(b, "CAJERO", a.username());
        for (String guessed : List.of(a.username(), a.user(), a.username() + "@example.test", "Synthetic User")) {
            denied(SELF, OIDC.token(guessed, a.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        }
        bind(a);
        assertThat(snapshot()).isEqualTo(bound);
    }

    @Test
    void missingOrBlankSubjectAndUnboundSubjectNeverFallBackToUsername() throws Exception {
        var i = fixture();
        bind(i);
        var before = snapshot();
        for (String subject : new String[]{null, "", "   "}) {
            denied(SELF, OIDC.token(subject, i.username(), "CAJERO"), "FORBIDDEN", "Se requiere un JWT con subject");
            assertThat(snapshot()).isEqualTo(before);
        }
        denied(SELF, OIDC.token("unbound:" + UUID.randomUUID(), i.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        self(i, "CAJERO", i.username());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void inactiveAndMissingTargetsRejectWithoutReservingSubjectAndBoundInactiveUserCannotRead() throws Exception {
        var i = fixture();
        rejected(UUID.randomUUID().toString(), i.subject(), "NOT_FOUND", "Usuario no encontrado");
        success("mutation { actualizarUsuario(id: \"" + i.user()
                + "\", input: {rolCodigo: \"RECEPCIONISTA\", activo: false}) { id activo } }", admin());
        rejected(i.user(), i.subject(), "BAD_REQUEST", "El usuario está inactivo");
        success("mutation { actualizarUsuario(id: \"" + i.user()
                + "\", input: {rolCodigo: \"RECEPCIONISTA\", activo: true}) { id activo } }", admin());
        bind(i);
        success("mutation { actualizarUsuario(id: \"" + i.user()
                + "\", input: {rolCodigo: \"RECEPCIONISTA\", activo: false}) { id activo } }", admin());
        var before = snapshot();
        denied(SELF, OIDC.token(i.subject(), i.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        denied(binding(i.user(), i.subject()), admin(), "BAD_REQUEST", "El usuario está inactivo");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void opaqueSubjectIsUnchangedWithCodePointBoundariesAndExactRepeatHasOneAudit() throws Exception {
        var i = fixture();
        String invalid = "El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco";
        for (String subject : List.of("", "   ", "X".repeat(101), "😀".repeat(101))) {
            rejected(i.user(), subject, "BAD_REQUEST", invalid);
        }
        String subject = " Case:" + UUID.randomUUID() + "😀".repeat(57) + " ";
        assertThat(subject.codePointCount(0, subject.length())).isEqualTo(100);
        var before = snapshot();
        var opaque = new Identity(i.user(), subject, i.username());
        bind(opaque);
        var after = snapshot();
        assertThat(after.path("listarAuditLogs").size()).isEqualTo(before.path("listarAuditLogs").size() + 1);
        assertThat(after.path("listarUsuarios")).isEqualTo(before.path("listarUsuarios"));
        assertThat(after.path("listarOdontologos")).isEqualTo(before.path("listarOdontologos"));
        self(opaque, "CAJERO", i.username());
        for (String changed : List.of(subject.trim(), subject.toLowerCase(java.util.Locale.ROOT))) {
            denied(SELF, OIDC.token(changed, i.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        }
        bind(opaque);
        assertThat(snapshot()).isEqualTo(after);
    }

    @Test
    void conflictingOwnerOverwriteAndSwapFailAtomicallyPreservingBothBindings() throws Exception {
        var a = fixture();
        var b = fixture();
        bind(a);
        var before = snapshot();
        denied(binding(b.user(), a.subject()), admin(), "BAD_REQUEST", CONFLICT);
        denied(binding(a.user(), b.subject()), admin(), "BAD_REQUEST", CONFLICT);
        assertThat(snapshot()).isEqualTo(before);
        denied(SELF, OIDC.token(b.subject(), b.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        bind(b);
        before = snapshot();
        denied(binding(a.user(), b.subject()), admin(), "BAD_REQUEST", CONFLICT);
        denied(binding(b.user(), a.subject()), admin(), "BAD_REQUEST", CONFLICT);
        self(a, "CAJERO", b.username());
        self(b, "CAJERO", a.username());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void rolesAndNoSubjectArgumentAreEnforcedWithoutWrites() throws Exception {
        var i = fixture();
        var before = snapshot();
        for (String role : List.of("CAJERO", "ODONTOLOGO", "RECEPCIONISTA", "UNKNOWN")) {
            denied(binding(i.user(), i.subject()), OIDC.token(i.subject(), i.username(), role), "FORBIDDEN", null);
            assertThat(snapshot()).isEqualTo(before);
        }
        bind(i);
        before = snapshot();
        for (String role : List.of("ODONTOLOGO", "RECEPCIONISTA", "UNKNOWN")) {
            denied(SELF, OIDC.token(i.subject(), i.username(), role), "FORBIDDEN", null);
            assertThat(snapshot()).isEqualTo(before);
        }
        var invalid = response("{ obtenerMiUsuarioId(subject: \"ignored\") }", admin());
        assertThat(invalid.path("errors").get(0).path("message").asText()).contains("UnknownArgument").contains("subject");
        self(i, "CAJERO", i.username());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void anonymousForgedExpiredWrongIssuerAndWrongAudienceFailAtHttpBoundary() throws Exception {
        var i = fixture();
        bind(i);
        var before = snapshot();
        var original = SignedJWT.parse(OIDC.token(i.subject(), i.username(), "ADMIN"));
        var wrongAud = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(OIDC.key.getKeyID()).build(),
                new JWTClaimsSet.Builder(original.getJWTClaimsSet()).audience("other-service").build());
        wrongAud.sign(new RSASSASigner(OIDC.key));
        var invalidTokens = List.of("", "Bearer " + OIDC.token(EmbeddedOidc.newKey(), OIDC.issuer,
                        i.subject(), i.username(), "ADMIN", Instant.now().plusSeconds(300)),
                "Bearer " + OIDC.token(OIDC.key, OIDC.issuer, i.subject(), i.username(), "ADMIN", Instant.now().minusSeconds(600)),
                "Bearer " + OIDC.token(OIDC.key, OIDC.issuer + "/wrong", i.subject(), i.username(), "ADMIN", Instant.now().plusSeconds(300)),
                "Bearer " + wrongAud.serialize());
        for (String query : List.of(SELF, binding(i.user(), i.subject()))) {
            for (String token : invalidTokens) {
                var http = graphql(port, query, Map.of("Authorization", token, "X-Dev-User", "admin"));
                assertThat(http.statusCode()).isEqualTo(401);
                assertThat(http.body()).isEmpty();
                assertThat(snapshot()).isEqualTo(before);
            }
        }
        self(i, "CAJERO", i.username());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void concurrentCompetingClaimsHaveOneBindingAndOneAuditWithReusableLoser() throws Exception {
        var a = fixture();
        var b = fixture();
        String subject = "race:" + UUID.randomUUID();
        var before = snapshot();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> claim(a, subject, ready, start));
        var second = CompletableFuture.supplyAsync(() -> claim(b, subject, ready, start));
        boolean bothReady = ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        assertThat(bothReady).isTrue();
        boolean aWon = first.get(30, TimeUnit.SECONDS);
        boolean bWon = second.get(30, TimeUnit.SECONDS);
        assertThat(List.of(aWon, bWon)).containsExactlyInAnyOrder(true, false);
        var after = snapshot();
        assertThat(after.path("listarUsuarios")).isEqualTo(before.path("listarUsuarios"));
        assertThat(after.path("listarOdontologos")).isEqualTo(before.path("listarOdontologos"));
        assertThat(after.path("listarAuditLogs").size()).isEqualTo(before.path("listarAuditLogs").size() + 1);
        var winner = aWon ? a : b;
        var loser = aWon ? b : a;
        self(new Identity(winner.user(), subject, loser.username()), "CAJERO", loser.username());
        denied(SELF, OIDC.token(loser.subject(), loser.username(), "CAJERO"), "NOT_FOUND", UNAVAILABLE);
        assertThat(snapshot()).isEqualTo(after);
        bind(loser);
        self(loser, "CAJERO", loser.username());
        self(new Identity(winner.user(), subject, winner.username()), "CAJERO", winner.username());
    }

    private boolean claim(Identity i, String subject, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            var body = response(binding(i.user(), subject), admin());
            if (body.has("errors")) {
                assertThat(body.path("data").isNull()).isTrue();
                assertThat(body.path("errors").size()).isEqualTo(1);
                var error = body.path("errors").get(0);
                assertThat(error.path("extensions").path("classification").asText()).isEqualTo("BAD_REQUEST");
                assertThat(error.path("message").asText()).isIn(CONFLICT,
                        "El registro viola una restricción de unicidad o integridad");
                return false;
            }
            assertThat(body.path("data").path("vincularIdentidadUsuario").asText()).isEqualTo(i.user());
            return true;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
