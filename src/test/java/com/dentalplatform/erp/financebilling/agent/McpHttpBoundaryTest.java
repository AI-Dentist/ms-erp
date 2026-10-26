package com.dentalplatform.erp.financebilling.agent;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Streamable HTTP transport: authentication, session setup, tool dispatch and method security. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class McpHttpBoundaryTest {
    private static final String DATABASE = "erp_mcp_http";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) throws Exception {
        try (var c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection(); var s = c.createStatement()) {
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

    private String user(String role) {
        String name = "mcp-" + UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (rol_id, username, email, nombre_completo, activo) "
                + "SELECT id, ?, ?, 'MCP Test', true FROM roles WHERE codigo = ?", name, name + "@test.invalid", role);
        return name;
    }

    private HttpResponse<String> post(String user, String session, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                // Protocol traffic must not enter the business-response replay cache.
                .header("Idempotency-Key", " ")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (user != null) { request.header("X-Dev-User", user); }
        if (session != null) { request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18"); }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> rpc(int id, String method, Object params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private JsonNode payload(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        if (body.stripLeading().startsWith("{")) { return JSON.readTree(body); }
        return JSON.readTree(body.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).strip()).findFirst().orElseThrow());
    }

    private String initialize(String user) throws Exception {
        var response = post(user, null, rpc(1, "initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "erp-test", "version", "1"))));
        assertThat(payload(response).path("result").path("serverInfo").path("name").asString()).isNotBlank();
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(post(user, session, Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).statusCode())
                .isEqualTo(202);
        return session;
    }

    @Test
    void unauthenticatedTransportIsUnauthorized() throws Exception {
        assertThat(post(null, null, rpc(1, "tools/list", Map.of())).statusCode()).isEqualTo(401);
    }

    @Test
    void adminWritesAreAuditedAndReusingTheSessionNeverGrantsItsRoleToACashier() throws Exception {
        String admin = user("ADMIN");
        String cashier = user("CAJERO");
        String session = initialize(admin);
        String code = "MCP-OK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        var created = payload(post(admin, session, rpc(2, "tools/call", Map.of("name", "crearServicioOdontologicoTool",
                "arguments", Map.of("codigo", code, "nombre", "MCP Service", "precioBs", 12.50)))));
        assertThat(created.has("error") || created.path("result").path("isError").asBoolean()).as(created.toString()).isFalse();
        UUID service = jdbc.queryForObject("SELECT id FROM servicios_odontologicos WHERE codigo = ?", UUID.class, code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs a JOIN usuarios u ON u.id = a.usuario_id "
                + "WHERE u.username = ? AND a.entidad_id = ? AND a.accion = 'SERVICIO_ODONTOLOGICO_CREADO'",
                Integer.class, admin, service)).isEqualTo(1);
        String forbidden = code + "-DENY";
        var denied = payload(post(cashier, session, rpc(3, "tools/call", Map.of("name", "crearServicioOdontologicoTool",
                "arguments", Map.of("codigo", forbidden, "nombre", "Denied", "precioBs", 10)))));
        assertThat(denied.has("error") || denied.path("result").path("isError").asBoolean()).as(denied.toString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos WHERE codigo = ?", Integer.class, forbidden)).isZero();
    }

    @Test
    void authenticatedCashierCanReadButCannotCreateServices() throws Exception {
        String cashier = user("CAJERO");
        String session = initialize(cashier);
        var listed = payload(post(cashier, session, rpc(2, "tools/list", Map.of())));
        assertThat(listed.path("result").path("tools").size()).isEqualTo(9);
        assertThat(listed.toString()).contains("obtenerResumenFinancieroTool", "crearServicioOdontologicoTool");
        var read = payload(post(cashier, session, rpc(3, "tools/call", Map.of("name", "obtenerResumenFinancieroTool",
                "arguments", Map.of()))));
        assertThat(read.path("error").isMissingNode()).as(read.toString()).isTrue();
        assertThat(read.path("result").path("isError").asBoolean()).as(read.toString()).isFalse();
        assertThat(read.toString()).contains("saldoPendienteTotalBs");

        int before = jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos", Integer.class);
        String code = "MCP-DENY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        var denied = payload(post(cashier, session, rpc(4, "tools/call", Map.of("name", "crearServicioOdontologicoTool",
                "arguments", Map.of("codigo", code, "nombre", "Denied", "precioBs", 10)))));
        assertThat(denied.has("error") || denied.path("result").path("isError").asBoolean()).as(denied.toString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos WHERE codigo = ?", Integer.class, code)).isZero();
    }
}
