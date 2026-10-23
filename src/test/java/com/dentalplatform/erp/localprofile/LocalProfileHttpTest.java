package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
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

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.ORIGIN;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.preflight;
import static org.assertj.core.api.Assertions.assertThat;

/** Profile {@code local} end to end: CORS preflight, X-Dev-User authentication, role enforcement, replay scoping. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LocalProfileHttpTest {

    private static final String DATABASE = "erp_local_profile";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    static final String ADMIN = "adm-" + SUFFIX;
    static final String CASHIER = "caj-" + SUFFIX;
    static final String CASHIER_2 = "caj2-" + SUFFIX;
    static final String INACTIVE = "off-" + SUFFIX;

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
    }

    private void user(String username, String role, boolean active) {
        jdbc.update("""
                INSERT INTO usuarios (rol_id, username, email, nombre_completo, activo)
                SELECT id, ?, ?, 'Synthetic Local User', ? FROM roles WHERE codigo = ?
                ON CONFLICT (username) DO NOTHING""", username, username + "@local.test", active, role);
    }

    private void users() {
        user(ADMIN, "ADMIN", true);
        user(CASHIER, "CAJERO", true);
        user(CASHIER_2, "CAJERO", true);
        user(INACTIVE, "ADMIN", false);
    }

    private static Map<String, String> as(String username) {
        return Map.of("X-Dev-User", username);
    }

    private static Map<String, String> plus(Map<String, String> headers, String name, String value) {
        var copy = new HashMap<>(headers);
        copy.put(name, value);
        return copy;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void preflightFromFrontendOriginIsAllowedWithExactPolicy() throws Exception {
        for (String path : new String[]{"/graphql", "/api/v1/reports/export/pdf"}) {
            HttpResponse<String> response = preflight(port, path, ORIGIN, "POST",
                    "authorization,content-type,idempotency-key,x-dev-user");
            assertThat(response.statusCode()).isEqualTo(200);
            var headers = response.headers();
            assertThat(headers.allValues("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
            assertThat(headers.firstValue("Access-Control-Allow-Methods").orElseThrow())
                    .contains("GET", "POST", "OPTIONS").doesNotContain("PUT", "DELETE", "*");
            assertThat(headers.firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase())
                    .contains("authorization", "content-type", "idempotency-key", "x-dev-user").doesNotContain("*");
            assertThat(headers.firstValue("Access-Control-Allow-Credentials")).isEmpty();
        }
    }

    @Test
    void preflightRejectsOtherOriginsMethodsAndHeaders() throws Exception {
        List<HttpResponse<String>> denied = List.of(
                preflight(port, "/graphql", "http://evil.example", "POST", "content-type"),
                preflight(port, "/graphql", "http://localhost:4200", "POST", "content-type"),
                preflight(port, "/graphql", ORIGIN, "PUT", "content-type"),
                preflight(port, "/graphql", ORIGIN, "POST", "x-evil"));
        for (var response : denied) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }

    @Test
    void actualResponseExposesReplayAndDispositionHeaders() throws Exception {
        users();
        var response = graphql(port, "{ listarRoles { codigo } }", plus(as(ADMIN), "Origin", ORIGIN));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
                .contains("Idempotency-Replayed", "Content-Disposition");
    }

    @Test
    void devUserAdminRunsAdminQuery() throws Exception {
        users();
        var response = graphql(port, "{ listarUsuarios { username } }", as(ADMIN));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(ADMIN).doesNotContain("errors");
    }

    @Test
    void devUserCashierGetsOnlyItsOwnRoleAndIsForbiddenOnAdminMutation() throws Exception {
        users();
        int usuarios = count("usuarios");
        int audits = count("audit_logs");
        String mutation = "mutation { crearUsuario(input: {rolCodigo: \"ADMIN\", username: \"esc-" + SUFFIX
                + "\", email: \"esc-" + SUFFIX + "@local.test\", nombreCompleto: \"Escalation\", password: "
                + "\"Sup3r-secret-pass\"}) { id } }";
        var denied = graphql(port, mutation, as(CASHIER));
        assertThat(denied.statusCode()).isEqualTo(200);
        assertThat(denied.body()).contains("FORBIDDEN");
        assertThat(count("usuarios")).isEqualTo(usuarios);
        assertThat(count("audit_logs")).isEqualTo(audits);
        assertThat(graphql(port, "{ listarRoles { codigo } }", as(CASHIER)).body()).doesNotContain("errors");
    }

    @Test
    void missingUnknownBlankInactiveAndBearerOnlyRequestsAreUnauthorizedWithoutMutation() throws Exception {
        users();
        int usuarios = count("usuarios");
        int audits = count("audit_logs");
        int keys = count("idempotency_keys");
        String query = "{ listarUsuarios { username } }";
        for (Map<String, String> headers : List.of(
                Map.<String, String>of(),
                as("ghost-" + SUFFIX),
                as(INACTIVE),
                as(" "),
                Map.of("Authorization", "Bearer not-a-real-token"),
                Map.of("Authorization", "Bearer not-a-real-token", "X-Dev-User", ADMIN))) {
            var response = graphql(port, query, headers);
            assertThat(response.statusCode()).as(headers.toString()).isEqualTo(401);
            assertThat(response.body()).doesNotContain(ADMIN);
        }
        var withKey = plus(as("ghost-" + SUFFIX), "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(graphql(port, query, withKey).statusCode()).isEqualTo(401);
        assertThat(count("usuarios")).isEqualTo(usuarios);
        assertThat(count("audit_logs")).isEqualTo(audits);
        assertThat(count("idempotency_keys")).isEqualTo(keys);
    }

    @Test
    void sameKeyIsReplayedToSamePrincipalButNeverToAnotherDevUser() throws Exception {
        users();
        String query = "{ listarRoles { codigo } }";
        String key = UUID.randomUUID().toString();
        var first = graphql(port, query, plus(as(CASHIER), "Idempotency-Key", key));
        assertThat(first.statusCode()).isEqualTo(200);
        var replay = graphql(port, query, plus(as(CASHIER), "Idempotency-Key", key));
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(replay.body()).isEqualTo(first.body());
        var other = graphql(port, query, plus(as(CASHIER_2), "Idempotency-Key", key));
        assertThat(other.statusCode()).isEqualTo(422);
        assertThat(other.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(other.body()).doesNotContain("listarRoles");
    }
}
