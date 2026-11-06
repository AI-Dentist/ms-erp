package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic loopback identity provider and public HTTP fixtures for offline sync tests. */
final class OfflineSyncHttpSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final RSAKey IDP = key();
    private static final HttpServer OIDC = oidc();
    private static final String ISSUER = "http://127.0.0.1:" + OIDC.getAddress().getPort() + "/realms/offline-sync-test";
    private static final String SNAPSHOT = """
            { listarUsuarios { id username activo rol { codigo } }
              listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt }
              listarPacientes { id ciNit nombres apellidos activo }
              listarOrdenesCobro { id estado saldoPendienteBs cuotas { id estado } }
              obtenerFlujoCaja(fechaInicio: "1970-01-01T00:00:00Z", fechaFin: "9999-01-01T00:00:00Z") {
                totalIngresosBs totalEgresosBs saldoNetoBs movimientos { id tipoMovimiento montoBs recibo { id } } } }
            """;

    private OfflineSyncHttpSupport() { }

    static void configure(DynamicPropertyRegistry registry, String database) throws Exception {
        try (Connection connection = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            if (!statement.executeQuery("SELECT 1 FROM pg_database WHERE datname='" + database + "'").next()) {
                statement.execute("CREATE DATABASE " + database);
            }
        }
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(database));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> false);
        registry.add("erp.catalog-outbox.enabled", () -> false);
        registry.add("erp.billing-saga.outbox.enabled", () -> false);
        registry.add("erp.n8n.enabled", () -> false);
        registry.add("erp.receipts.mode", () -> "local");
        registry.add("erp.pagofacil.mock-enabled", () -> true);
        registry.add("spring.ai.openai.api-key", () -> "not-configured");
        registry.add("erp.security.local-dev-user.jwt-issuer-uri", () -> ISSUER);
    }

    static void close() { OIDC.stop(0); }

    static String admin() { return adminToken(); }

    static HttpResponse<String> send(int port, String query, Map<String, Object> variables,
                                     String token, String key, String operationName) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("query", query);
        body.put("variables", variables);
        if (operationName != null) body.put("operationName", operationName);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(String body) { return JSON.readTree(body); }

    static void error(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode errors = json(response.body()).path("errors");
        assertThat(errors.isArray()).as(response.body()).isTrue();
        assertThat(errors.size()).as(response.body()).isEqualTo(1);
        assertThat(errors.get(0).path("extensions").path("code").asText()).as(response.body()).isEqualTo(code);
    }

    record Identity(String id, String username, String subject) { }

    static Identity createAdmin(int port) throws Exception {
        return createUser(port, "ADMIN");
    }

    static Identity createUser(int port, String role) throws Exception {
        String username = "sync-" + UUID.randomUUID().toString().substring(0, 18);
        String subject = "sync-subject-" + UUID.randomUUID();
        Map<String, Object> user = Map.of("rolCodigo", role, "username", username,
                "email", username + "@example.test", "nombreCompleto", "Synthetic Sync User",
                "password", "Synthetic2026!");
        String query = """
                mutation($input: CrearUsuarioConIdentidadInput!) {
                  crearUsuarioConIdentidad(input: $input) { id username activo rol { codigo } }
                }
                """;
        JsonNode created = success(port, query, Map.of("input", Map.of("usuario", user,
                "keycloakSubject", subject)), adminToken()).path("crearUsuarioConIdentidad");
        assertThat(created.path("username").asText()).isEqualTo(username);
        assertThat(created.path("activo").asBoolean()).isTrue();
        assertThat(created.path("rol").path("codigo").asText()).isEqualTo(role);
        String id = created.path("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();
        return new Identity(id, username, subject);
    }

    static JsonNode snapshot(int port) throws Exception {
        return success(port, SNAPSHOT, Map.of(), adminToken());
    }

    static JsonNode success(int port, String query, Map<String, Object> variables, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("query", query, "variables", variables))))
                .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.has("errors")).as(body.toString()).isFalse();
        return body.path("data");
    }

    static String token(Identity identity) {
        return token(identity.subject(), identity.username());
    }

    static String token(Identity identity, String role) {
        Instant now = Instant.now();
        return token(identity.subject(), identity.username(), null, now.minusSeconds(5), now.plusSeconds(300), role);
    }

    private static String adminToken() { return token("fixture-admin", "admin-scz"); }

    static String token(Identity identity, Object authTime, Instant issuedAt, Instant expiresAt) {
        return token(identity.subject(), identity.username(), authTime, issuedAt, expiresAt);
    }

    static String token(Identity identity, String role, Object authTime, Instant issuedAt, Instant expiresAt) {
        return token(identity.subject(), identity.username(), authTime, issuedAt, expiresAt, role);
    }

    private static String token(String subject, String username) {
        Instant now = Instant.now();
        return token(subject, username, null, now.minusSeconds(5), now.plusSeconds(300));
    }

    private static String token(String subject, String username, Object authTime, Instant issuedAt, Instant expiresAt) {
        return token(subject, username, authTime, issuedAt, expiresAt, "ADMIN");
    }

    private static String token(String subject, String username, Object authTime, Instant issuedAt, Instant expiresAt, String role) {
        try {
            var builder = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("ms-erp")
                    .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(role)))
                    .issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt));
            if (authTime != null) builder.claim("auth_time", authTime);
            var claims = builder.build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(IDP.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(IDP));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static HttpServer oidc() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/offline-sync-test";
                String body = exchange.getRequestURI().getPath().endsWith("/jwks")
                        ? new JWKSet(IDP.toPublicJWK()).toString()
                        : "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer
                                + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
