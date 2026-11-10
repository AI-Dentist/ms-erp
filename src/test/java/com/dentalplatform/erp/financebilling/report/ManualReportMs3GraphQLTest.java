package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManualReportMs3GraphQLTest extends IntegrationTestBase {
    static final String DATABASE = "erp_ms3_report";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpServer MS3_SERVER = startMs3Stub();
    static final String GENERATE = """
            mutation($i: ReporteManualInput!) { generarReporteManual(input: $i) {
              reporteId titulo columnas filas filasJson filasTruncadas tipoGraficoSugerido resumenAnalitico
              consultaSqlEjecutada sqlGenerado urlDescargaPdf urlDescargaExcel
            } }
            """;

    static final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    static final AtomicReference<String> lastQuery = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();
    static final AtomicInteger lastStatusCode = new AtomicInteger(200);
    static final AtomicReference<String> stubResponseBody = new AtomicReference<>();

    @Autowired WebApplicationContext web;
    @Autowired JsonMapper json;
    MockMvc mvc;

    static HttpServer startMs3Stub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/agendamiento/reportes/", exchange -> {
                lastPath.set(exchange.getRequestURI().getPath());
                lastQuery.set(exchange.getRequestURI().getQuery());
                lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                int code = lastStatusCode.get();
                byte[] bytes = (stubResponseBody.get() != null ? stubResponseBody.get() : "{}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start MS3 stub server", e);
        }
    }

    @AfterAll
    static void stopMs3Stub() {
        MS3_SERVER.stop(0);
    }

    @DynamicPropertySource
    static void ms3Properties(DynamicPropertyRegistry registry) {
        try (Connection c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            var exists = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'");
            if (!exists.next()) {
                s.execute("CREATE DATABASE " + DATABASE);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create " + DATABASE, e);
        }
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        registry.add("erp.ms3.reports-base-url",
                () -> "http://127.0.0.1:" + MS3_SERVER.getAddress().getPort() + "/api/agendamiento");
        registry.add("erp.ms3.connect-timeout-ms", () -> 1000);
        registry.add("erp.ms3.read-timeout-ms", () -> 2000);
    }

    @BeforeEach
    void init() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        lastAuthHeader.set(null);
        lastQuery.set(null);
        lastPath.set(null);
        lastStatusCode.set(200);
        stubResponseBody.set(null);
    }

    private <T> T asAdminJwt(String subject, String username, String tokenValue, Supplier<T> action) {
        var old = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject(subject)
                .claim("preferred_username", username).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_ADMIN"), username));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(old);
        }
    }

    private GraphQlTester.Response executeGenerate(Map<String, Object> input) {
        return graphQl.document(GENERATE).variable("i", input).execute();
    }

    @Test
    @SuppressWarnings("unchecked")
    void citasReportEnrichesNamesForwardsBearerAndExports() throws Exception {
        // Known seeded IDs in V2__seed_bolivia_data.sql:
        String pacienteId = "60000000-0000-0000-0000-000000000001"; // Carlos Andrés Justiniano Moreno
        String odontologoId = "50000000-0000-0000-0000-000000000001"; // Valeria Roca Antelo
        String consultorioId = "30000000-0000-0000-0000-000000000001"; // Consultorio 1 - Planta Baja

        String ms3Json = """
                {
                  "success": true,
                  "data": {
                    "entidad": "citas",
                    "columnas": [
                      {"clave": "citaId", "etiqueta": "ID Cita", "tipo": "TEXTO"},
                      {"clave": "pacienteId", "etiqueta": "ID Paciente", "tipo": "TEXTO"},
                      {"clave": "odontologoId", "etiqueta": "ID Odontólogo", "tipo": "TEXTO"},
                      {"clave": "consultorioId", "etiqueta": "ID Consultorio", "tipo": "TEXTO"},
                      {"clave": "fechaHora", "etiqueta": "Fecha y Hora", "tipo": "FECHA_HORA"},
                      {"clave": "duracionMin", "etiqueta": "Duración (min)", "tipo": "NUMERO"},
                      {"clave": "tipoTratamiento", "etiqueta": "Tipo de tratamiento", "tipo": "TEXTO"},
                      {"clave": "estado", "etiqueta": "Estado", "tipo": "TEXTO"},
                      {"clave": "reservadaEn", "etiqueta": "Reservada en", "tipo": "FECHA_HORA"},
                      {"clave": "probabilidadNoShow", "etiqueta": "Probabilidad No-Show", "tipo": "NUMERO"},
                      {"clave": "nivelRiesgo", "etiqueta": "Riesgo No-Show", "tipo": "TEXTO"}
                    ],
                    "filas": [
                      {
                        "citaId": "CITA-100",
                        "pacienteId": "%s",
                        "odontologoId": "%s",
                        "consultorioId": "%s",
                        "fechaHora": "2026-03-05 10:00",
                        "duracionMin": 45,
                        "tipoTratamiento": "Endodoncia",
                        "estado": "RESERVADA",
                        "reservadaEn": "2026-03-01 08:30",
                        "probabilidadNoShow": 0.08,
                        "nivelRiesgo": "BAJO"
                      }
                    ],
                    "total": 1
                  },
                  "timestamp": "2026-03-01T12:00:00Z"
                }
                """.formatted(pacienteId, odontologoId, consultorioId);
        stubResponseBody.set(ms3Json);

        Map<String, Object> input = Map.of(
                "entidad", "citas",
                "columnas", List.of("citaId", "pacienteNombre", "odontologoNombre", "consultorioNombre",
                        "fechaHora", "duracionMin", "tipoTratamiento", "estado", "reservadaEn",
                        "probabilidadNoShow", "nivelRiesgo"),
                "fechaInicio", "2026-03-01T00:00:00-04:00",
                "fechaFin", "2026-03-31T23:59:59-04:00",
                "estado", "RESERVADA"
        );

        Map<String, Object> report = asAdminJwt("admin-sub", "admin-ms3", "jwt-token-sample-123", () -> {
            var r = executeGenerate(input);
            return r.path("generarReporteManual").entity(Map.class).get();
        });

        // 1. Verify upstream request details
        assertThat(lastPath.get()).isEqualTo("/api/agendamiento/reportes/citas");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer jwt-token-sample-123");
        assertThat(lastQuery.get()).contains("desde=2026-03-01")
                .contains("hasta=2026-03-31")
                .contains("estado=RESERVADA");

        // 2. Verify payload and catalog enrichment
        assertThat(report.get("titulo").toString()).contains("Reservas / Citas");
        assertThat(report.get("consultaSqlEjecutada")).isEqualTo("MS3 GET /api/agendamiento/reportes/citas");
        assertThat(report.get("sqlGenerado")).isEqualTo("MS3 GET /api/agendamiento/reportes/citas");
        assertThat((Boolean) report.get("filasTruncadas")).isFalse();

        List<List<String>> filas = (List<List<String>>) report.get("filas");
        assertThat(filas).hasSize(1);
        List<String> row = filas.getFirst();
        assertThat(row.get(0)).isEqualTo("CITA-100");
        assertThat(row.get(1)).isEqualTo("Carlos Andrés Justiniano Moreno");
        assertThat(row.get(2)).isEqualTo("Valeria Roca Antelo");
        assertThat(row.get(3)).isEqualTo("Consultorio 1 - Planta Baja");
        assertThat(row.get(4)).isEqualTo("2026-03-05 10:00");
        assertThat(row.get(5)).isEqualTo("45");
        assertThat(row.get(6)).isEqualTo("Endodoncia");
        assertThat(row.get(7)).isEqualTo("RESERVADA");
        assertThat(row.get(8)).isEqualTo("2026-03-01 08:30");
        assertThat(row.get(9)).isEqualTo("0.08");
        assertThat(row.get(10)).isEqualTo("BAJO");

        // 3. Verify export endpoints with real XLSX and PDF
        String reportId = report.get("reporteId").toString();
        byte[] xlsx = mvc.perform(get("/api/v1/reports/export/" + reportId + "/xlsx").with(user("admin-ms3").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(Arrays.copyOf(xlsx, 2))).isEqualTo("PK");
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(11).getCell(0).getStringCellValue()).isEqualTo("CITA-100");
            assertThat(sheet.getRow(11).getCell(1).getStringCellValue()).isEqualTo("Carlos Andrés Justiniano Moreno");
        }

        byte[] pdf = mvc.perform(get("/api/v1/reports/export/" + reportId + "/pdf").with(user("admin-ms3").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(Arrays.copyOf(pdf, 4))).isEqualTo("%PDF");
    }

    @Test
    @SuppressWarnings("unchecked")
    void insumosReportGeneratesAndExports() throws Exception {
        String ms3Json = """
                {
                  "success": true,
                  "data": {
                    "entidad": "insumos",
                    "columnas": [
                      {"clave": "insumoId", "etiqueta": "ID Insumo", "tipo": "TEXTO"},
                      {"clave": "nombre", "etiqueta": "Insumo", "tipo": "TEXTO"},
                      {"clave": "unidad", "etiqueta": "Unidad", "tipo": "TEXTO"},
                      {"clave": "stockActual", "etiqueta": "Stock actual", "tipo": "NUMERO"},
                      {"clave": "stockMinimo", "etiqueta": "Stock mínimo", "tipo": "NUMERO"},
                      {"clave": "bajoMinimo", "etiqueta": "Bajo mínimo", "tipo": "BOOLEANO"},
                      {"clave": "salidasPeriodo", "etiqueta": "Salidas en el período", "tipo": "NUMERO"}
                    ],
                    "filas": [
                      {
                        "insumoId": "INS-001",
                        "nombre": "Anestesia Lidocaína 2%",
                        "unidad": "caja",
                        "stockActual": 15,
                        "stockMinimo": 20,
                        "bajoMinimo": true,
                        "salidasPeriodo": 8
                      }
                    ],
                    "total": 1
                  },
                  "timestamp": "2026-03-01T12:00:00Z"
                }
                """;
        stubResponseBody.set(ms3Json);

        Map<String, Object> input = Map.of(
                "entidad", "insumos",
                "columnas", List.of("insumoId", "nombre", "unidad", "stockActual", "stockMinimo", "bajoMinimo", "salidasPeriodo"),
                "fechaInicio", "2026-03-01T00:00:00-04:00",
                "fechaFin", "2026-03-31T23:59:59-04:00"
        );

        Map<String, Object> report = asAdminJwt("admin-sub", "admin-ms3", "jwt-token-sample-456", () -> {
            var r = executeGenerate(input);
            return r.path("generarReporteManual").entity(Map.class).get();
        });

        assertThat(lastPath.get()).isEqualTo("/api/agendamiento/reportes/insumos");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer jwt-token-sample-456");
        assertThat(report.get("titulo").toString()).contains("Inventario / Stock de Insumos");

        List<List<String>> filas = (List<List<String>>) report.get("filas");
        assertThat(filas).hasSize(1);
        List<String> row = filas.getFirst();
        assertThat(row.get(0)).isEqualTo("INS-001");
        assertThat(row.get(1)).isEqualTo("Anestesia Lidocaína 2%");
        assertThat(row.get(5)).isEqualTo("Sí");

        String reportId = report.get("reporteId").toString();
        byte[] xlsx = mvc.perform(get("/api/v1/reports/export/" + reportId + "/xlsx").with(user("admin-ms3").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(Arrays.copyOf(xlsx, 2))).isEqualTo("PK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncationReportedWhenTotalExceeds500() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++) {
            rows.add(Map.of(
                    "insumoId", "INS-" + i,
                    "nombre", "Insumo " + i,
                    "unidad", "unidad",
                    "stockActual", 100,
                    "stockMinimo", 10,
                    "bajoMinimo", false,
                    "salidasPeriodo", 5
            ));
        }
        Map<String, Object> envelope = Map.of(
                "success", true,
                "data", Map.of(
                        "entidad", "insumos",
                        "columnas", List.of(Map.of("clave", "insumoId", "etiqueta", "ID Insumo", "tipo", "TEXTO")),
                        "filas", rows,
                        "total", 850
                ),
                "timestamp", "2026-03-01T12:00:00Z"
        );
        stubResponseBody.set(json.writeValueAsString(envelope));

        Map<String, Object> input = Map.of(
                "entidad", "insumos",
                "columnas", List.of("insumoId", "nombre"),
                "fechaInicio", "2026-03-01T00:00:00-04:00",
                "fechaFin", "2026-03-31T23:59:59-04:00"
        );

        Map<String, Object> report = asAdminJwt("admin-sub", "admin-ms3", "jwt-token-trunc", () -> {
            var r = executeGenerate(input);
            return r.path("generarReporteManual").entity(Map.class).get();
        });

        assertThat((Boolean) report.get("filasTruncadas")).isTrue();
        assertThat(report.get("resumenAnalitico").toString()).contains("Se muestran los primeros 500; hay más registros.");
        List<?> filas = (List<?>) report.get("filas");
        assertThat(filas).hasSize(500);
    }

    @Test
    void ms3HttpErrorProducesServiceUnavailableAndStoresNothing() throws Exception {
        String sentinel = fillReportCache();
        var before = exportedCells(sentinel);
        lastStatusCode.set(503);
        stubResponseBody.set("{\"error\":\"upstream failure\"}");

        Map<String, Object> input = Map.of(
                "entidad", "insumos",
                "columnas", List.of("insumoId", "nombre"),
                "fechaInicio", "2026-03-01T00:00:00-04:00",
                "fechaFin", "2026-03-31T23:59:59-04:00"
        );

        asAdminJwt("admin-sub", "admin-ms3", "jwt-err", () -> {
            var r = executeGenerate(input);
            r.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
            });
            return null;
        });

        assertThat(exportedCells(sentinel)).isEqualTo(before);
    }

    @ParameterizedTest(name = "reject malformed MS3 payload: {0}")
    @ValueSource(strings = {"empty-row", "wrong-entity", "fractional-total"})
    void malformedEntityRowsAndTotalNeverStoreReports(String defect) throws Exception {
        String sentinel = fillReportCache();
        var before = exportedCells(sentinel);
        String body = validInsumoEnvelope();
        body = switch (defect) {
            case "empty-row" -> """
                    {"success":true,"data":{"entidad":"insumos","columnas":[],"total":1,"filas":[{}]}}
                    """;
            case "wrong-entity" -> body.replace("\"entidad\":\"insumos\"", "\"entidad\":\"citas\"");
            case "fractional-total" -> body.replace("\"total\":1", "\"total\":1.5");
            default -> throw new AssertionError(defect);
        };
        stubResponseBody.set(body);
        asAdminJwt("admin-sub", "admin-ms3", "jwt-malformed", () -> {
            var response = executeGenerate(Map.of("entidad", "insumos", "columnas", List.of("insumoId", "nombre")));
            response.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(errors.getFirst().getMessage())
                        .isEqualTo("SERVICE_UNAVAILABLE: No se pudo obtener el reporte de MS3");
            });
            response.path("generarReporteManual").pathDoesNotExist();
            return null;
        });
        // The oldest of 64 real reports must survive: even one unintended put would evict it.
        assertThat(exportedCells(sentinel)).isEqualTo(before);
    }

    private static String validInsumoEnvelope() {
        return """
                {"success":true,"data":{"entidad":"insumos","columnas":[],"total":1,"filas":[
                  {"insumoId":"INS-1","nombre":"Synthetic","unidad":null,"stockActual":1.25,
                   "stockMinimo":2,"bajoMinimo":true,"salidasPeriodo":0}]}}
                """;
    }

    private String fillReportCache() {
        return asAdminJwt("admin-sub", "admin-ms3", "jwt-sentinel", () -> {
            String oldest = null;
            for (int i = 0; i < 64; i++) {
                String id = executeGenerate(Map.of("entidad", "servicios", "columnas", List.of("codigo", "nombre")))
                        .path("generarReporteManual.reporteId").entity(String.class).get();
                if (oldest == null) oldest = id;
            }
            return oldest;
        });
    }

    private List<List<String>> exportedCells(String reportId) throws Exception {
        byte[] bytes = mvc.perform(get("/api/v1/reports/export/" + reportId + "/xlsx")
                        .with(user("admin-ms3").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var result = new ArrayList<List<String>>();
            for (var row : workbook.getSheetAt(0)) {
                var cells = new ArrayList<String>();
                for (var cell : row) cells.add(cell.toString());
                result.add(cells);
            }
            return result;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-entity", "overflow-total", "negative-total", "string-total",
            "excess-rows", "missing-name", "object-name", "string-stock", "null-stock", "array-row"})
    void malformedRowAndTotalBoundariesFailClosed(String defect) {
        String body = validInsumoEnvelope();
        body = switch (defect) {
            case "missing-entity" -> body.replace("\"entidad\":\"insumos\",", "");
            case "overflow-total" -> body.replace("\"total\":1", "\"total\":9223372036854775808");
            case "negative-total" -> body.replace("\"total\":1", "\"total\":-1");
            case "string-total" -> body.replace("\"total\":1", "\"total\":\"1\"");
            case "excess-rows" -> body.replace("\"total\":1", "\"total\":0");
            case "missing-name" -> body.replace("\"nombre\":\"Synthetic\",", "");
            case "object-name" -> body.replace("\"nombre\":\"Synthetic\"", "\"nombre\":{}");
            case "string-stock" -> body.replace("\"stockActual\":1.25", "\"stockActual\":\"1.25\"");
            case "null-stock" -> body.replace("\"stockActual\":1.25", "\"stockActual\":null");
            case "array-row" -> """
                    {"success":true,"data":{"entidad":"insumos","total":1,"filas":[[]]}}
                    """;
            default -> throw new AssertionError(defect);
        };
        stubResponseBody.set(body);
        asAdminJwt("admin-sub", "admin-ms3", "jwt-boundary", () -> {
            var response = executeGenerate(Map.of("entidad", "insumos", "columnas", List.of("insumoId", "nombre")));
            response.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(errors.getFirst().getMessage())
                        .isEqualTo("SERVICE_UNAVAILABLE: No se pudo obtener el reporte de MS3");
            });
            response.path("generarReporteManual").pathDoesNotExist();
            return null;
        });
    }

    @Test
    void nullableUnitAndDecimalStockRemainExportableAsNumbers() throws Exception {
        stubResponseBody.set(validInsumoEnvelope());
        String id = asAdminJwt("admin-sub", "admin-ms3", "jwt-nullable", () -> {
            var response = executeGenerate(Map.of("entidad", "insumos",
                    "columnas", List.of("insumoId", "unidad", "stockActual", "stockMinimo", "salidasPeriodo")));
            response.path("generarReporteManual.filas").entityList(List.class)
                    .satisfies(rows -> assertThat(rows.getFirst()).containsExactly("INS-1", null, "1.25", "2", "0"));
            return response.path("generarReporteManual.reporteId").entity(String.class).get();
        });
        byte[] bytes = mvc.perform(get("/api/v1/reports/export/" + id + "/xlsx")
                        .with(user("admin-ms3").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var row = workbook.getSheetAt(0).getRow(11);
            assertThat(row.getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(1.25);
            assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(2);
            assertThat(row.getCell(4).getNumericCellValue()).isZero();
        }
    }

    @Test
    void unavailableMlAndNullableAppointmentFieldsRemainNull() {
        stubResponseBody.set("""
                {"success":true,"data":{"entidad":"citas","total":1,"columnas":[],"filas":[{
                  "citaId":"CITA-NULL","pacienteId":"60000000-0000-0000-0000-000000000001",
                  "odontologoId":"50000000-0000-0000-0000-000000000001","consultorioId":null,
                  "fechaHora":"2026-03-05 10:00","duracionMin":30,"estado":"RESERVADA",
                  "tipoTratamiento":null,"reservadaEn":null,"probabilidadNoShow":null,"nivelRiesgo":null}]}}
                """);
        asAdminJwt("admin-sub", "admin-ms3", "jwt-ml-null", () -> {
            executeGenerate(Map.of("entidad", "citas", "columnas",
                            List.of("citaId", "consultorioNombre", "tipoTratamiento", "reservadaEn",
                                    "probabilidadNoShow", "nivelRiesgo")))
                    .path("generarReporteManual.filas").entityList(List.class)
                    .satisfies(rows -> assertThat(rows.getFirst()).containsExactly("CITA-NULL", null, null, null, null, null));
            return null;
        });
    }

    @Test
    void nullableDurationAndUnavailableMlReturnActualRow() {
        stubResponseBody.set(appointmentWithDuration("null"));
        asAdminJwt("admin-sub", "admin-ms3", "jwt-duration-null", () -> {
            var response = executeGenerate(Map.of("entidad", "citas", "columnas",
                    List.of("citaId", "duracionMin", "probabilidadNoShow", "nivelRiesgo")));
            response.path("generarReporteManual.filas").entityList(List.class).satisfies(rows -> {
                assertThat(rows).hasSize(1);
                assertThat(rows.getFirst()).containsExactly("CITA-DURATION", null, null, null);
            });
            response.path("generarReporteManual.filasJson").entity(String.class)
                    .isEqualTo("[[\"CITA-DURATION\",null,null,null]]");
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"30\"", "true", "{}", "[]"})
    void invalidDurationTypesRemainServiceUnavailable(String duration) {
        stubResponseBody.set(appointmentWithDuration(duration));
        asAdminJwt("admin-sub", "admin-ms3", "jwt-duration-invalid", () -> {
            var response = executeGenerate(Map.of("entidad", "citas", "columnas",
                    List.of("citaId", "duracionMin", "probabilidadNoShow", "nivelRiesgo")));
            response.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(errors.getFirst().getMessage())
                        .isEqualTo("SERVICE_UNAVAILABLE: No se pudo obtener el reporte de MS3");
            });
            response.path("generarReporteManual").pathDoesNotExist();
            return null;
        });
    }

    private static String appointmentWithDuration(String duration) {
        return """
                {"success":true,"data":{"entidad":"citas","total":1,"columnas":[],"filas":[{
                  "citaId":"CITA-DURATION","pacienteId":"60000000-0000-0000-0000-000000000001",
                  "odontologoId":"50000000-0000-0000-0000-000000000001","consultorioId":null,
                  "fechaHora":"2026-03-05 10:00","duracionMin":%s,"estado":"RESERVADA",
                  "tipoTratamiento":null,"reservadaEn":null,"probabilidadNoShow":null,"nivelRiesgo":null}]}}
                """.formatted(duration);
    }

    @Test
    void ms3MalformedResponseProducesServiceUnavailable() {
        // total says 10, but filas has only 2 (less than expected min(total, 500))
        String malformedJson = """
                {
                  "success": true,
                  "data": {
                    "entidad": "insumos",
                    "columnas": [],
                    "filas": [{"insumoId": "1"}, {"insumoId": "2"}],
                    "total": 10
                  }
                }
                """;
        stubResponseBody.set(malformedJson);

        Map<String, Object> input = Map.of(
                "entidad", "insumos",
                "columnas", List.of("insumoId"),
                "fechaInicio", "2026-03-01T00:00:00-04:00",
                "fechaFin", "2026-03-31T23:59:59-04:00"
        );

        asAdminJwt("admin-sub", "admin-ms3", "jwt-malformed", () -> {
            var r = executeGenerate(input);
            r.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
            });
            return null;
        });
    }

    @Test
    void ms3UnresolvedIdentityProducesServiceUnavailable() {
        // Random UUID not present in MS1 database
        String unknownPacienteId = UUID.randomUUID().toString();
        String odontologoId = "50000000-0000-0000-0000-000000000001";
        String ms3Json = """
                {
                  "success": true,
                  "data": {
                    "entidad": "citas",
                    "columnas": [],
                    "filas": [
                      {
                        "citaId": "CITA-999",
                        "pacienteId": "%s",
                        "odontologoId": "%s",
                        "fechaHora": "2026-03-05 10:00",
                        "duracionMin": 30,
                        "tipoTratamiento": "Control",
                        "estado": "RESERVADA"
                      }
                    ],
                    "total": 1
                  }
                }
                """.formatted(unknownPacienteId, odontologoId);
        stubResponseBody.set(ms3Json);

        Map<String, Object> input = Map.of(
                "entidad", "citas",
                "columnas", List.of("citaId", "pacienteNombre"),
                "fechaInicio", "2026-03-01T00:00:00-04:00",
                "fechaFin", "2026-03-31T23:59:59-04:00"
        );

        asAdminJwt("admin-sub", "admin-ms3", "jwt-unresolved", () -> {
            var r = executeGenerate(input);
            r.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
            });
            return null;
        });
    }

    @Test
    void missingCallerJwtProducesServiceUnavailable() {
        // Authenticated as ADMIN with non-JWT authentication (e.g. UsernamePasswordAuthenticationToken)
        var old = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin-no-jwt", "password", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        SecurityContextHolder.setContext(context);
        try {
            Map<String, Object> input = Map.of(
                    "entidad", "insumos",
                    "columnas", List.of("insumoId"),
                    "fechaInicio", "2026-03-01T00:00:00-04:00",
                    "fechaFin", "2026-03-31T23:59:59-04:00"
            );

            var r = executeGenerate(input);
            r.errors().satisfy(errors -> {
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("SERVICE_UNAVAILABLE");
            });
        } finally {
            SecurityContextHolder.setContext(old);
        }
    }
}
