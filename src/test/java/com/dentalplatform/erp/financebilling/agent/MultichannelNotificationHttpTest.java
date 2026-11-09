package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MultichannelNotificationHttpTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    static final List<String> headers = new CopyOnWriteArrayList<>();
    static final AtomicInteger webhookStatus = new AtomicInteger(200);
    static final HttpServer webhook = webhook();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;
    String admin, session;

    static HttpServer webhook() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/erp-notificaciones", exchange -> {
                bodies.add(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                headers.add(exchange.getRequestHeaders().getFirst("X-ERP-Webhook-Secret"));
                int status = webhookStatus.get();
                if (status == 0) { exchange.close(); return; }
                if (status == -1) { try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } status = 200; }
                try { exchange.sendResponseHeaders(status, -1); } finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        try (var c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection(); var s = c.createStatement()) {
            if (!s.executeQuery("SELECT 1 FROM pg_database WHERE datname='erp_multichannel'").next()) { s.execute("CREATE DATABASE erp_multichannel"); }
        }
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl("erp_multichannel"));
        r.add("spring.datasource.username", () -> "postgres"); r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false); r.add("erp.billing-saga.outbox.enabled", () -> false);
        r.add("erp.n8n.enabled", () -> true);
        r.add("erp.n8n.webhook-url", () -> "http://127.0.0.1:" + webhook.getAddress().getPort() + "/erp-notificaciones");
        r.add("erp.n8n.webhook-secret", () -> "synthetic-t10-header");
        r.add("erp.n8n.connect-timeout-ms", () -> 100); r.add("erp.n8n.read-timeout-ms", () -> 100);
    }
    @AfterAll static void stop() { webhook.stop(0); }
    @BeforeEach void prepare() throws Exception {
        bodies.clear(); headers.clear(); webhookStatus.set(200);
        admin = user("ADMIN");
        var initialized = request(admin, null, "/mcp", rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "t10", "version", "1"))));
        assertThat(initialized.statusCode()).isEqualTo(200);
        session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(request(admin, session, "/mcp", Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).statusCode()).isEqualTo(202);
    }
    String user(String role) {
        String name = "t10-" + UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (rol_id,username,email,nombre_completo,activo) SELECT id,?,?, 'Synthetic T10',true FROM roles WHERE codigo=?", name, name + "@test.invalid", role);
        return name;
    }
    Map<String,Object> rpc(String method, Object params) { return Map.of("jsonrpc", "2.0", "id", 20, "method", method, "params", params); }
    HttpResponse<String> request(String user, String session, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("X-Dev-User", user).header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (session != null) { request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18"); }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode rpcBody(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String body = response.body();
        return JSON.readTree(body.stripLeading().startsWith("{") ? body : body.lines().filter(s -> s.startsWith("data:")).map(s -> s.substring(5).strip()).findFirst().orElseThrow());
    }
    JsonNode call(String tool, Map<String,Object> args) throws Exception {
        var response = rpcBody(request(admin, session, "/mcp", rpc("tools/call", Map.of("name", tool, "arguments", args))));
        assertThat(response.has("error") || response.path("result").path("isError").asBoolean()).as(response.toString()).isFalse();
        return JSON.readTree(response.path("result").path("content").get(0).path("text").asString());
    }
    Map<String,Object> arguments() {
        return new HashMap<>(Map.of("pacienteNombre", "Juan Pérez", "telefono", "+59170012345", "email", "paciente@ejemplo.com",
                "montoBs", 350, "concepto", "Cuota 2/3 - Tratamiento de Ortodoncia", "fechaVencimiento", "2026-11-05"));
    }
    void results(JsonNode result, String mode, String state, List<String> channels) {
        assertThat(result.path("exito").asBoolean()).isTrue();
        assertThat(result.path("modoEjecucion").asString()).isEqualTo(mode);
        assertThat(result.path("resultadosPorCanal").size()).isEqualTo(channels.size());
        var providers = Map.of("WHATSAPP", "META_CLOUD_TEST_NUMBER", "TELEGRAM", "TELEGRAM_BOT_API", "GMAIL", "GMAIL_SMTP");
        var destinations = Map.of("WHATSAPP", "+59170012345", "TELEGRAM", "Chat Clínica", "GMAIL", "paciente@ejemplo.com");
        for (int i=0; i<channels.size(); i++) {
            var row = result.path("resultadosPorCanal").get(i); String channel = channels.get(i);
            assertThat(row.path("canal").asString()).isEqualTo(channel);
            assertThat(row.path("proveedor").asString()).isEqualTo(providers.get(channel));
            assertThat(row.path("estado").asString()).isEqualTo(state);
            assertThat(row.path("destino").asString()).isEqualTo(destinations.get(channel));
        }
    }
    @Test void mcpAdvertisesStringOrArrayChannelsAndKeepsAllLegacyTools() throws Exception {
        var tools = rpcBody(request(admin, session, "/mcp", rpc("tools/list", Map.of()))).path("result").path("tools");
        assertThat(tools.size()).isEqualTo(15);
        int canonical = 0;
        for (JsonNode tool : tools) {
            if (List.of("dispararNotificacionMulticanal", "dispararAlertaCobranzaWhatsapp").contains(tool.path("name").asString())) {
                canonical++;
                var channels = tool.path("inputSchema").path("properties").path("canales");
                List<String> types = new ArrayList<>();
                channels.path("anyOf").forEach(option -> types.add(option.path("type").asString()));
                assertThat(types).as(channels.toString()).containsExactlyInAnyOrder("string", "array", "null");
            }
        }
        assertThat(canonical).isEqualTo(2);
    }

    @Test void liveSendsTheExactAuthenticatedPayloadAndReturnsThreeDestinations() throws Exception {
        results(call("dispararNotificacionMulticanal", arguments()), "N8N_LIVE", "ENVIADO", List.of("WHATSAPP", "TELEGRAM", "GMAIL"));
        assertThat(bodies).hasSize(1); assertThat(headers).containsExactly("synthetic-t10-header");
        assertThat(bodies.getFirst()).isEqualTo(JSON.readTree("""
            {"tipoEvento":"NOTIFICACION_COBRANZA_MULTICANAL","canalesActivos":["WHATSAPP","TELEGRAM","GMAIL"],
             "destinatario":{"nombre":"Juan Pérez","telefono":"+59170012345","email":"paciente@ejemplo.com","telegramChatId":""},
             "datosFinancieros":{"moneda":"BOB","simbolo":"Bs","monto":350.00,"concepto":"Cuota 2/3 - Tratamiento de Ortodoncia","fechaVencimiento":"2026-11-05"},
             "mensajeFormateado":"Clínica Dental: Estimado(a) Juan Pérez, le recordamos que tiene pendiente el pago de Bs 350.00 por concepto de Cuota 2/3 - Tratamiento de Ortodoncia (Vencimiento: 2026-11-05)."}
            """));
    }
    @Test void channelFormsAndAliasDefaultsAreAdditive() throws Exception {
        for (Object channels : List.of("TODOS", "WHATSAPP,TELEGRAM,GMAIL", List.of("WHATSAPP","TELEGRAM","GMAIL"), List.of())) {
            var args = arguments(); args.put("canales", channels); args.remove("email"); args.put("telegramChatId", " ");
            results(call("dispararAlertaCobranzaWhatsapp", args), "N8N_LIVE", "ENVIADO", List.of("WHATSAPP","TELEGRAM","GMAIL"));
            assertThat(bodies.getLast().path("destinatario").path("telegramChatId").asString()).isEmpty();
        }
        var args = arguments(); args.put("canales", "GMAIL");
        results(call("dispararNotificacionMulticanal", args), "N8N_LIVE", "ENVIADO", List.of("GMAIL"));
        var legacy = call("dispararAlertaCobranzaWhatsapp", Map.of("cuotaIds", List.of(UUID.randomUUID().toString())));
        assertThat(legacy.has("notificacionEnviada")).isTrue();
    }
    @Test void cashierCanSendToAnExplicitTelegramDestinationAndHttpRejectionIsNotSimulation() throws Exception {
        var args = arguments(); args.put("canales", List.of("TELEGRAM", "TELEGRAM")); args.put("telegramChatId", "-100012345");
        var rpc = rpcBody(request(user("CAJERO"), session, "/mcp", rpc("tools/call", Map.of("name", "dispararNotificacionMulticanal", "arguments", args))));
        assertThat(rpc.has("error") || rpc.path("result").path("isError").asBoolean()).isFalse();
        var result = JSON.readTree(rpc.path("result").path("content").get(0).path("text").asString());
        assertThat(result.path("resultadosPorCanal").size()).isEqualTo(1);
        assertThat(result.path("resultadosPorCanal").get(0).path("destino").asString()).isEqualTo("-100012345");
        assertThat(bodies.getLast().path("destinatario").path("telegramChatId").asString()).isEqualTo("-100012345");
        webhookStatus.set(503);
        var rejected = call("dispararNotificacionMulticanal", arguments());
        assertThat(rejected.path("exito").asBoolean()).isFalse();
        assertThat(rejected.path("modoEjecucion").asString()).isEqualTo("NO_ENVIADO");
        rejected.path("resultadosPorCanal").forEach(row -> assertThat(row.path("estado").asString()).isEqualTo("NO_ENVIADO"));
    }

    @Test void droppedConnectionAndTimeoutSimulateWithoutLoggingPatientData() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.dentalplatform.erp.financebilling.application.usecase.DispararNotificacionMulticanalUseCase");
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            for (int status : new int[]{0,-1}) {
                webhookStatus.set(status);
                results(call("dispararNotificacionMulticanal", arguments()), "SIMULADO_SIN_N8N", "SIMULADO_OK", List.of("WHATSAPP","TELEGRAM","GMAIL"));
            }
            assertThat(logs.list).isNotEmpty();
            assertThat(logs.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("Juan Pérez", "+59170012345", "paciente@ejemplo.com", "synthetic-t10-header", "Ortodoncia"));
            assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("SIMULADO_SIN_N8N", "REDACTED"));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test void invalidRequestsAreRejectedBeforeAnyWebhookCall() throws Exception {
        for (var invalid : List.of(Map.<String,Object>of("telefono","70012345"), Map.<String,Object>of("email","invalid"),
                Map.<String,Object>of("montoBs",-1), Map.<String,Object>of("montoBs",0), Map.<String,Object>of("montoBs",10000000000L), Map.<String,Object>of("montoBs",1.001), Map.<String,Object>of("fechaVencimiento","2026-02-30"), Map.<String,Object>of("canales","SMS"), Map.<String,Object>of("canales",List.of(1)))) {
            var args = arguments(); args.putAll(invalid);
            var response = rpcBody(request(admin, session, "/mcp", rpc("tools/call", Map.of("name","dispararNotificacionMulticanal","arguments",args))));
            assertThat(response.has("error") || response.path("result").path("isError").asBoolean()).isTrue();
        }
        assertThat(bodies).isEmpty();
    }
    @Test void receptionistCannotNotifyUsingAnAdminMcpSession() throws Exception {
        var response = rpcBody(request(user("RECEPCIONISTA"), session, "/mcp", rpc("tools/call", Map.of("name","dispararNotificacionMulticanal","arguments",arguments()))));
        assertThat(response.has("error") || response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(bodies).isEmpty();
    }
    @Test void explicitNullChannelsDefaultsToAll() throws Exception {
        var args = arguments(); args.put("canales", null); args.put("email", null); args.put("telegramChatId", null);
        results(call("dispararNotificacionMulticanal", args), "N8N_LIVE", "ENVIADO", List.of("WHATSAPP","TELEGRAM","GMAIL"));
    }


}
