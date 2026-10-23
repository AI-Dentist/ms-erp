package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.JSON;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Profile {@code local} with Bearer JWTs from a loopback issuer shaped like the local Keycloak realm. The seeded
 * synthetic users (admin-scz, caja-scz, recepcion-scz) are resolved by {@code preferred_username}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LocalProfileJwtHttpTest {

    private static final String DATABASE = "erp_local_jwt";
    private static final EmbeddedOidc OIDC = EmbeddedOidc.started();
    private static final String LIST_USERS = "{ listarUsuarios { username } }";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
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
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.security.local-dev-user.jwt-issuer-uri", () -> OIDC.issuer);
    }

    @AfterAll
    static void close() {
        OIDC.close();
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static String mutation() {
        return "mutation { crearEspecialidad(codigo: \"KC-" + UUID.randomUUID().toString().substring(0, 8)
                + "\", nombre: \"Synthetic Bearer\") { id } }";
    }

    private List<Object> snapshot() {
        List<Object> result = new ArrayList<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            result.add(jdbc.queryForList("SELECT to_jsonb(t)::text FROM \"" + table + "\" t ORDER BY to_jsonb(t)::text"));
        }
        for (String sequence : jdbc.queryForList("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename", String.class)) {
            result.add(jdbc.queryForList("SELECT last_value, is_called FROM \"" + sequence + "\""));
        }
        return result;
    }

    @Test
    void validAdminBearerIsAcceptedAndAuditedAsTheSeededDatabaseUser() throws Exception {
        String admin = OIDC.token(UUID.randomUUID().toString(), "admin-scz", "ADMIN");
        var response = graphql(port, mutation(), bearer(admin));
        assertThat(response.statusCode()).isEqualTo(200);
        String id = JSON.readTree(response.body()).path("data").path("crearEspecialidad").path("id").asText();
        assertThat(id).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT u.username FROM audit_logs a JOIN usuarios u ON u.id=a.usuario_id WHERE a.entidad_id=?",
                String.class, UUID.fromString(id))).isEqualTo("admin-scz");
        assertThat(graphql(port, LIST_USERS, bearer(admin)).body()).contains("recepcion-scz").doesNotContain("errors");
    }

    @Test
    void realmRolesBehaveLikeTheXDevUserRoleOfTheSameDatabaseUser() throws Exception {
        for (var user : List.of("caja-scz:CAJERO", "recepcion-scz:RECEPCIONISTA")) {
            String username = user.split(":")[0];
            var viaHeader = graphql(port, LIST_USERS, Map.of("X-Dev-User", username));
            var viaBearer = graphql(port, LIST_USERS, bearer(OIDC.token(UUID.randomUUID().toString(), username, user.split(":")[1])));
            assertThat(viaBearer.statusCode()).isEqualTo(viaHeader.statusCode()).isEqualTo(200);
            assertThat(viaBearer.body()).contains("FORBIDDEN").doesNotContain("admin-scz");
            assertThat(viaHeader.body()).contains("FORBIDDEN");
        }
        var before = snapshot();
        var denied = graphql(port, mutation(), bearer(OIDC.token(UUID.randomUUID().toString(), "caja-scz", "CAJERO")));
        assertThat(denied.body()).contains("FORBIDDEN").doesNotContain("Synthetic Bearer");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void bearerTakesPrecedenceOverXDevUserAndInvalidBearerNeverFallsBackToIt() throws Exception {
        String cashier = OIDC.token(UUID.randomUUID().toString(), "caja-scz", "CAJERO");
        var headers = Map.of("Authorization", "Bearer " + cashier, "X-Dev-User", "admin-scz");
        assertThat(graphql(port, LIST_USERS, headers).body()).contains("FORBIDDEN");
        var before = snapshot();
        var invalid = Map.of("Authorization", "Bearer not-a-jwt", "X-Dev-User", "admin-scz");
        assertThat(graphql(port, mutation(), invalid).statusCode()).isEqualTo(401);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(graphql(port, LIST_USERS, Map.of("X-Dev-User", "admin-scz")).body())
                .contains("admin-scz").doesNotContain("errors");
    }

    @Test
    void expiredWrongIssuerAndWrongSignatureTokensAreRejectedWithoutMutation() throws Exception {
        var before = snapshot();
        List<String> invalid = List.of(
                OIDC.token(OIDC.key, OIDC.issuer, "s", "admin-scz", "ADMIN", Instant.now().minusSeconds(120)),
                OIDC.token(OIDC.key, OIDC.issuer + "-other", "s", "admin-scz", "ADMIN", Instant.now().plusSeconds(300)),
                OIDC.token(EmbeddedOidc.newKey(), OIDC.issuer, "s", "admin-scz", "ADMIN", Instant.now().plusSeconds(300)));
        for (String token : invalid) {
            var response = graphql(port, mutation(), bearer(token));
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
            assertThat(snapshot()).isEqualTo(before);
        }
        assertThat(graphql(port, mutation(), Map.of()).statusCode()).isEqualTo(401);
    }
}
