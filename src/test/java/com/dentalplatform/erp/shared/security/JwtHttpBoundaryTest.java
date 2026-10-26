package com.dentalplatform.erp.shared.security;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** No mock authentication: discovery, JWKS, RS256 and bearer HTTP cross the production chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtHttpBoundaryTest {
    static final RSAKey KEY = key();
    static final HttpServer OIDC = issuer();
    static final String ISSUER = "http://127.0.0.1:" + OIDC.getAddress().getPort();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl("postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        // Deliberately omit dev-mode: the missing-property default must be the JWT chain.
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
    }

    @AfterAll static void closeIssuer() { OIDC.stop(0); HTTP.close(); }

    static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static HttpServer issuer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String base = "http://127.0.0.1:" + server.getAddress().getPort();
                String body = exchange.getRequestURI().getPath().equals("/jwks")
                        ? new JWKSet(KEY.toPublicJWK()).toString()
                        : "{\"issuer\":\"" + base + "\",\"jwks_uri\":\"" + base
                        + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
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

    String token(String subject, String username, String role) throws Exception {
        return token(KEY, ISSUER, subject, username, role, Instant.now().plusSeconds(300));
    }

    String token(RSAKey signingKey, String issuer, String subject, String username, String role, Instant expiry)
            throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(role)))
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiry)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    HttpResponse<String> post(String token, String query, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("query", query))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    // Includes every stored row and sequence, not merely the target aggregate's count.
    List<Object> snapshot() {
        List<Object> result = new ArrayList<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            result.add(jdbc.queryForList("SELECT to_jsonb(t)::text FROM \"" + table + "\" t ORDER BY to_jsonb(t)::text"));
        }
        for (String sequence : jdbc.queryForList("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename", String.class)) {
            result.add(jdbc.queryForList("SELECT last_value, is_called FROM \"" + sequence + "\""));
        }
        return result;
    }

    @Test void invalidBearerAndMissingBearerFailClosedWithoutMutation() throws Exception {
        var before = snapshot();
        for (String invalid : List.of(
                token(KEY, ISSUER + "/wrong", "admin-sub", "admin", "ADMIN", Instant.now().plusSeconds(300)),
                token(key(), ISSUER, "admin-sub", "admin", "ADMIN", Instant.now().plusSeconds(300)),
                token(KEY, ISSUER, "admin-sub", "admin", "ADMIN", Instant.now().minusSeconds(120)), "malformed")) {
            var response = post(invalid, mutation(), UUID.randomUUID().toString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
            assertThat(snapshot()).isEqualTo(before);
        }
        var anonymous = post(null, mutation(), "anonymous-key");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    static String mutation() {
        return "mutation { crearEspecialidad(codigo: \"JWT-" + UUID.randomUUID().toString().substring(0, 8)
                + "\", nombre: \"Synthetic JWT\") { id codigo } }";
    }

    @Test void signedRealmRolesEnforceMethodAndRestSecurity() throws Exception {
        String cashier = token("cashier-sub", "cajero", "CAJERO");
        var before = snapshot();
        var denied = post(cashier, mutation(), null);
        assertThat(denied.statusCode()).isEqualTo(200); // GraphQL carries method denials as FORBIDDEN errors.
        assertThat(denied.body()).contains("FORBIDDEN").doesNotContain("Synthetic JWT");
        var rest = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/reports/export/" + UUID.randomUUID() + "/pdf")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + cashier).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rest.statusCode()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(post(cashier, "{ listarRoles { codigo } }", null).body()).contains("ADMIN").doesNotContain("errors");
    }

    @Test void signedAdminMutationAndQueryUseTokenPrincipal() throws Exception {
        String admin = token("signed-admin-subject", "admin", "ADMIN");
        var response = post(admin, mutation(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var id = JSON.readTree(response.body()).path("data").path("crearEspecialidad").path("id").asText();
        assertThat(id).isNotEmpty();
        assertThat(post(admin, "{ listarEspecialidades { id } }", null).body()).contains(id).doesNotContain("errors");
        assertThat(jdbc.queryForObject("SELECT u.username FROM audit_logs a JOIN usuarios u ON u.id=a.usuario_id WHERE a.entidad_id=?",
                String.class, UUID.fromString(id))).isEqualTo("admin");
    }

    @Test void cachedAdminDataCannotCrossSignedSubjectsOrRoles() throws Exception {
        String key = UUID.randomUUID().toString();
        String query = "{ listarUsuarios { username email } }";
        String admin = token("admin-sub", "admin", "ADMIN");
        var first = post(admin, query, key);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("listarUsuarios").doesNotContain("errors");
        var before = snapshot();
        for (String other : List.of(token("other-sub", "admin", "ADMIN"), token("cash-sub", "admin", "CAJERO"),
                token("admin-sub", "admin", "CAJERO"))) {
            var rejected = post(other, query, key);
            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(rejected.body()).isEqualTo("{\"error\":\"La Idempotency-Key ya fue usada con una solicitud diferente\"}");
            assertThat(rejected.headers().firstValue("Idempotency-Replayed")).isEmpty();
            assertThat(snapshot()).isEqualTo(before);
        }
        var replay = post(admin, query, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        var renamed = post(token("admin-sub", "renamed-admin", "ADMIN"), query, key);
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(renamed.body()).isEqualTo(first.body());
        assertThat(renamed.headers().firstValue("Idempotency-Replayed")).contains("true");
    }
}
