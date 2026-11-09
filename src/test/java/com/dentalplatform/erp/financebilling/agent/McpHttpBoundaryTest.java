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
    @Autowired com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository orders;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
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

    private JsonNode call(String user, String session, String name, Map<String, Object> arguments) throws Exception {
        return payload(post(user, session, rpc(20, "tools/call", Map.of("name", name, "arguments", arguments))));
    }

    private void success(JsonNode response) {
        assertThat(response.has("error") || response.path("result").path("isError").asBoolean()).as(response.toString()).isFalse();
    }

    @Test
    void canonicalServicePersistsCategoryAndRejectsInvalidMoneyWithoutSideEffects() throws Exception {
        String admin = user("ADMIN"), session = initialize(admin), code = "CAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        var result = call(admin, session, "crearServicioArancel", Map.of("codigo", code, "nombre", "Arancel", "categoria", "PREVENCION", "precioBs", 12.50));
        success(result);
        assertThat(jdbc.queryForMap("SELECT categoria, precio_base_bs FROM servicios_odontologicos WHERE codigo = ?", code))
                .containsEntry("categoria", "PREVENCION").containsEntry("precio_base_bs", new java.math.BigDecimal("12.50"));
        int before = jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos", Integer.class);
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        for (double amount : new double[]{-1, 1.001, 10000000000d}) {
            var denied = call(admin, session, "crearServicioArancel", Map.of("codigo", code + "X", "nombre", "Invalid", "categoria", "X", "precioBs", amount));
            assertThat(denied.has("error") || denied.path("result").path("isError").asBoolean()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
    }

    @Test
    void canonicalExpenseValidatesAndUsesTheAuthenticatedCashier() throws Exception {
        String admin = user("ADMIN"), session = initialize(admin), description = "Glosa-" + UUID.randomUUID();
        success(call(admin, session, "registrarEgresoCaja", Map.of("montoBs", 10.25, "categoria", "INSUMOS", "glosa", description)));
        assertThat(jdbc.queryForMap("SELECT m.monto_bs, m.categoria, u.username FROM movimientos_caja m JOIN usuarios u ON u.id = m.usuario_cajero_id WHERE descripcion = ?", description))
                .containsEntry("monto_bs", new java.math.BigDecimal("10.25")).containsEntry("categoria", "INSUMOS").containsEntry("username", admin);
        int before = jdbc.queryForObject("SELECT count(*) FROM movimientos_caja", Integer.class);
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        for (var args : java.util.List.of(Map.<String,Object>of("montoBs", 0, "categoria", "INSUMOS", "glosa", description),
                Map.<String,Object>of("montoBs", 1.001, "categoria", "INSUMOS", "glosa", description),
                Map.<String,Object>of("montoBs", 10, "categoria", "", "glosa", description))) {
            var denied = call(admin, session, "registrarEgresoCaja", args);
            assertThat(denied.has("error") || denied.path("result").path("isError").asBoolean()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
    }

    @Test
    void canonicalRescheduleChangesOnlyOnePendingQuotaAndRejectsPaid() throws Exception {
        var order = com.dentalplatform.erp.financebilling.domain.model.OrdenCobro.crear(
                UUID.fromString("60000000-0000-0000-0000-000000000001"), null, null,
                java.util.List.of(com.dentalplatform.erp.financebilling.domain.model.OrdenCobroItem.crear(
                        UUID.fromString("70000000-0000-0000-0000-000000000001"), null, 1,
                        com.dentalplatform.erp.shared.domain.vo.Money.of("100"))), null, java.time.OffsetDateTime.now());
        order.configurarPlanDePagos(com.dentalplatform.erp.financebilling.domain.model.ModalidadPago.CREDITO,
                com.dentalplatform.erp.shared.domain.vo.Money.zero(), 2, java.time.LocalDate.now(), 30);
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s -> orders.guardar(order));
        var quota = order.getCuotas().getFirst();
        var other = order.getCuotas().getLast();
        String admin = user("ADMIN"), session = initialize(admin), date = java.time.LocalDate.now().plusDays(15).toString();
        success(call(admin, session, "reprogramarCuotaPlanPago", Map.of("cuotaId", quota.id().toString(), "nuevaFechaVencimiento", date)));
        assertThat(jdbc.queryForObject("SELECT fecha_vencimiento FROM cuotas_pago WHERE id = ?", java.time.LocalDate.class, quota.id())).isEqualTo(java.time.LocalDate.parse(date));
        assertThat(jdbc.queryForObject("SELECT fecha_vencimiento FROM cuotas_pago WHERE id = ?", java.time.LocalDate.class, other.id())).isEqualTo(other.fechaVencimiento());
        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s -> {
            var persisted = orders.bloquearPorId(order.getId()).orElseThrow();
            persisted.registrarPagoCuota(quota.id(), java.time.OffsetDateTime.now()); return orders.guardar(persisted);
        });
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        var denied = call(admin, session, "reprogramarCuotaPlanPago", Map.of("cuotaId", quota.id().toString(), "nuevaFechaVencimiento", "2030-01-01"));
        assertThat(denied.has("error") || denied.path("result").path("isError").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT fecha_vencimiento FROM cuotas_pago WHERE id = ?", java.time.LocalDate.class, quota.id())).isEqualTo(java.time.LocalDate.parse(date));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuotas_pago WHERE orden_cobro_id = ?", Integer.class, order.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
    }

    @Test
    void canonicalHealthReturnsAnExplicitReadOnlyExecutiveDiagnosis() throws Exception {
        String admin = user("ADMIN"), session = initialize(admin);
        int before = jdbc.queryForObject("SELECT count(*) FROM movimientos_caja", Integer.class);
        var health = call(admin, session, "auditarSaludNegocio", Map.of()); success(health);
        assertThat(health.toString()).contains("diagnostico", "accionesRecomendadas", "umbralEgresoInusualBs", "flujoCajaHoy", "cuotasVencidas");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja", Integer.class)).isEqualTo(before);
    }

    @Test
    void canonicalToolsNeverInheritTheRoleOfTheSessionCreator() throws Exception {
        String admin = user("ADMIN"), session = initialize(admin), receptionist = user("RECEPCIONISTA");
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        for (var tool : java.util.List.of(
                Map.of("name", "crearServicioArancel", "arguments", Map.of("codigo", "DENIED", "nombre", "Denied", "categoria", "X", "precioBs", 1)),
                Map.of("name", "registrarEgresoCaja", "arguments", Map.of("montoBs", 1, "categoria", "X", "glosa", "Denied")),
                Map.of("name", "auditarSaludNegocio", "arguments", Map.of()),
                Map.of("name", "dispararAlertaCobranzaWhatsapp", "arguments", Map.of()))) {
            var denied = payload(post(receptionist, session, rpc(30, "tools/call", tool)));
            assertThat(denied.has("error") || denied.path("result").path("isError").asBoolean()).as(denied.toString()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos WHERE codigo = 'DENIED'", Integer.class)).isZero();
    }

    @Test
    void canonicalWhatsappReportsNotSentWhenIntegrationIsDisabled() throws Exception {
        String admin = user("ADMIN"), session = initialize(admin);
        var alert = call(admin, session, "dispararAlertaCobranzaWhatsapp", Map.of()); success(alert);
        assertThat(alert.toString()).contains("notificacionEnviada", "false", "no se envi");
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
        assertThat(listed.path("result").path("tools").size()).isEqualTo(15);
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
