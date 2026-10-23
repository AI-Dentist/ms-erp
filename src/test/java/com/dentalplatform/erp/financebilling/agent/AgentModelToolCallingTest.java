package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.dentalplatform.erp.financebilling.agent.AgentSupport.EXECUTE;
import static org.assertj.core.api.Assertions.assertThat;

/** OpenAI-compatible loopback provider exercises the actual Spring AI model/tool loop without a real API key. */
class AgentModelToolCallingTest extends IntegrationTestBase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<String> TOOL = new AtomicReference<>();
    static final AtomicReference<Map<String, Object>> ARGS = new AtomicReference<>(Map.of());
    static final AtomicInteger SQL_CALLS = new AtomicInteger();
    static final HttpServer SERVER = start();

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", e -> {
                String request = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean sql = request.contains("Translate the untrusted user question into SQL");
                boolean finished = request.contains("tool_call_id");
                Object message;
                String reason;
                if (sql) {
                    SQL_CALLS.incrementAndGet();
                    message = Map.of("role", "assistant", "content", "SELECT code, price_bs FROM report_services LIMIT 10");
                    reason = "stop";
                } else if (finished) {
                    message = Map.of("role", "assistant", "content", "Resultado obtenido mediante herramientas del ERP.");
                    reason = "stop";
                } else {
                    message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call_test", "type", "function",
                            "function", Map.of("name", TOOL.get(), "arguments", JSON.writeValueAsString(ARGS.get())))));
                    reason = "tool_calls";
                }
                byte[] body = JSON.writeValueAsString(Map.of("id", "chat_test", "object", "chat.completion", "created", 1,
                        "model", "test", "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", reason))))
                        .getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length);
                e.getResponseBody().write(body);
                e.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.ai.openai.api-key", () -> "test-only-loopback-key");
        r.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1");
        r.add("spring.ai.retry.max-attempts", () -> 1);
        AgentSupport.reportingProperties(r);
    }

    @AfterAll static void stop() { SERVER.stop(0); }

    @org.junit.jupiter.api.BeforeEach
    void allowColdSdkInitialization() {
        // Cold SDK/tool initialization is outside the tester default 5 s; this is not an ERP latency SLA.
        // A finite test-only bound leaves provider and production timeouts unchanged.
        graphQl = graphQl.mutate().responseTimeout(java.time.Duration.ofSeconds(15)).build();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void modelToolCallUsesTheCallerAndTheRealUseCase() {
        String code = "MODEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        TOOL.set("crearServicioOdontologicoTool");
        ARGS.set(Map.of("codigo", code, "nombre", "Model Test", "precioBs", 12.50));
        var response = graphQl.document(EXECUTE).variable("i", "Crea un servicio codigo=" + code + " nombre=Model precio=12.50").execute();
        response.path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly(TOOL.get())
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .isEqualTo("Resultado obtenido mediante herramientas del ERP.");
        UUID id = jdbc.queryForObject("SELECT id FROM servicios_odontologicos WHERE codigo = ?", UUID.class, code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'SERVICIO_ODONTOLOGICO_CREADO' "
                + "AND entidad_id = ? AND usuario_id = '20000000-0000-0000-0000-000000000001'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "model-cashier", roles = "CAJERO")
    void forbiddenModelToolCannotWriteEvenIfTheProviderRequestsIt() {
        String code = "MODEL-DENY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        TOOL.set("crearServicioOdontologicoTool");
        ARGS.set(Map.of("codigo", code, "nombre", "Denied", "precioBs", 10));
        int before = jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos", Integer.class);
        graphQl.document(EXECUTE).variable("i", "Crear servicio codigo=" + code + " nombre=Denied precio=10").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos", Integer.class)).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void reportToolActuallyResolvesTheLazyGeneratorAndExecutesTheGuardedSql() {
        AgentSupport.prepareReportingRole(jdbc);
        TOOL.set("generarReporteDinamicoSqlTool");
        ARGS.set(Map.of("solicitud", "Precios de servicios"));
        int before = SQL_CALLS.get();
        graphQl.document(EXECUTE).variable("i", "Genera un reporte de precios de servicios").execute()
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly(TOOL.get())
                .path("ejecutarAgenteErp.reporteGenerado.consultaSqlEjecutada").entity(String.class)
                .isEqualTo("SELECT code, price_bs FROM report_services LIMIT 10")
                .path("ejecutarAgenteErp.reporteGenerado.reporteId").entity(UUID.class).satisfies(id -> assertThat(id).isNotNull());
        assertThat(SQL_CALLS.get()).isEqualTo(before + 1);
    }
}
