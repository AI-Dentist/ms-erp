package com.dentalplatform.erp.financebilling.report;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import com.dentalplatform.erp.financebilling.application.port.ReportQueryPort;
import com.dentalplatform.erp.financebilling.application.report.ReportStore;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(username = "report-admin", roles = "ADMIN")
class ReportsIntegrationTest extends IntegrationTestBase {
    private static final String ROLE = "report_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String QUERY = """
        query($q: String!) { generarReporteInteligente(pregunta: $q) {
          reporteId preguntaOriginal explicacionNatural consultaSqlEjecutada columnas filasJson
          tipoGraficoSugerido urlDescargaExcel urlDescargaPdf
        } }
        """;
    @MockitoBean(answers = org.mockito.Answers.CALLS_REAL_METHODS) ChatModel model;
    @Autowired ReportQueryPort queryPort;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean java.time.Clock clock;
    @Autowired WebApplicationContext web;
    private MockMvc mvc;

    @DynamicPropertySource
    static void reportingProperties(DynamicPropertyRegistry registry) {
        registry.add("erp.reports.db-url", ReportsIntegrationTest::reportingUrl);
        registry.add("erp.reports.db-user", () -> ROLE);
        registry.add("erp.reports.db-password", () -> PASSWORD);
        registry.add("spring.ai.openai.api-key", () -> "test-placeholder-never-used");
    }

    private static String reportingUrl() {
        // JDBC URL credentials override the username property: never reuse the embedded admin URL verbatim.
        return EmbeddedPostgresSupport.jdbcUrl("erp_it").replace("?user=postgres", "");
    }

    @BeforeEach
    void prepareReadOnlyRole() {
        // This fresh role exists only in the isolated embedded cluster. Never change application roles or PUBLIC.
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + ROLE + "') THEN "
                + "CREATE ROLE " + ROLE + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '" + PASSWORD + "'; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        jdbc.execute("GRANT SELECT ON report_income, report_orders, report_services TO " + ROLE);
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        givenSql("SELECT code, price_bs FROM report_services ORDER BY code LIMIT 2");
    }

    private void givenSql(String sql) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(sql)))));
    }

    @Test
    void originalQuestionDatesAndCategoryControlGeneratedSqlAndRealQueryResults() {
        // Test-only rows in the disposable embedded cluster; no patient data is read or sent to the model.
        for (Object[] row : new Object[][]{
                {"2090-01-10", "REPORT_TEST_A", 10}, {"2090-01-20", "REPORT_TEST_A", 20},
                {"2090-02-10", "REPORT_TEST_A", 40}, {"2090-01-10", "REPORT_TEST_B", 80}}) {
            jdbc.update("INSERT INTO movimientos_caja(id, usuario_cajero_id, tipo_movimiento, categoria, metodo_pago, monto_bs, descripcion, fecha_movimiento) VALUES (?, ?, 'INGRESO', ?, 'EFECTIVO', ?, 'Reporting test fixture', ?::timestamptz)",
                    UUID.randomUUID(), UUID.fromString("20000000-0000-0000-0000-000000000002"), row[1], row[2], row[0] + "T12:00:00-04:00");
        }
        for (String[] request : new String[][]{
                {"2090-01-01", "2090-01-31", "REPORT_TEST_A", "30"},
                {"2090-01-01", "2090-01-31", "REPORT_TEST_B", "80"},
                {"2090-02-01", "2090-02-28", "REPORT_TEST_A", "40"}}) {
            String question = "Ingresos entre " + request[0] + " y " + request[1] + ", categoría " + request[2];
            String sql = "SELECT SUM(amount_bs) FROM report_income WHERE day >= '" + request[0]
                    + "' AND day <= '" + request[1] + "' AND category = '" + request[2] + "'";
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                String generated = question.equals(prompt.getUserMessage().getText()) ? sql : "SELECT * FROM report_income LIMIT 500";
                return new ChatResponse(List.of(new Generation(new AssistantMessage(generated))));
            });
            String rows = graphQl.document(QUERY).variable("q", question).execute()
                    .path("generarReporteInteligente.preguntaOriginal").entity(String.class).isEqualTo(question)
                    .path("generarReporteInteligente.consultaSqlEjecutada").entity(String.class).isEqualTo(sql)
                    .path("generarReporteInteligente.columnas").entityList(String.class).containsExactly("sum")
                    .path("generarReporteInteligente.filasJson").entity(String.class).get();
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(rows);
            assertThat(json.size()).isEqualTo(1);
            assertThat(json.get(0).get(0).decimalValue()).isEqualByComparingTo(request[3]);
        }
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> {
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(prompt.getSystemMessage().getText()).contains("untrusted", "Preserve");
            assertThat(prompt.getUserMessage().getText()).startsWith("Ingresos entre 2090-");
        });
    }

    private String generate() {
        return graphQl.document(QUERY).variable("q", "Precios de servicios").execute()
                .path("generarReporteInteligente.preguntaOriginal").entity(String.class).isEqualTo("Precios de servicios")
                .path("generarReporteInteligente.explicacionNatural").entity(String.class).isEqualTo("Resultado agregado de las vistas de reportes; sin datos personales.")
                .path("generarReporteInteligente.consultaSqlEjecutada").entity(String.class).isEqualTo("SELECT code, price_bs FROM report_services ORDER BY code LIMIT 2")
                .path("generarReporteInteligente.columnas").entityList(String.class).containsExactly("code", "price_bs")
                .path("generarReporteInteligente.filasJson").entity(String.class).matches(s -> s.startsWith("[[") && s.contains("CIR-EXT") && s.contains("CIR-TMO"))
                .path("generarReporteInteligente.tipoGraficoSugerido").entity(String.class).isEqualTo("TABLA")
                .path("generarReporteInteligente.urlDescargaExcel").entity(String.class).matches(s -> s.startsWith("/api/v1/reports/export/xlsx?reporteId="))
                .path("generarReporteInteligente.urlDescargaPdf").entity(String.class).matches(s -> s.startsWith("/api/v1/reports/export/pdf?reporteId="))
                .path("generarReporteInteligente.reporteId").entity(String.class).get();
    }

    @Test
    void publicGraphQlAndBinaryExportsUseCachedResultsOnly() throws Exception {
        String id = generate();
        var xlsx = mvc.perform(get("/api/v1/reports/export/xlsx").param("reporteId", id))
                .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"reporte-" + id + ".xlsx\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("code");
            assertThat(book.getSheetAt(0).getRow(1).getCell(1).getNumericCellValue()).isGreaterThan(0);
        }
        byte[] pdf = mvc.perform(get("/api/v1/reports/export/pdf").param("reporteId", id))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"reporte-" + id + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        try (var reader = new org.openpdf.text.pdf.PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isPositive();
            assertThat(new org.openpdf.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1)).contains("code", "price_bs");
        }
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void rejectedSqlDoesNotChangeAnyPublicRowsOrSequences() {
        String snapshot = snapshot();
        for (String sql : new String[]{"DELETE FROM pacientes", "SELECT nextval('recibo_numero_seq') FROM report_orders",
                "SELECT * FROM report_orders; UPDATE ordenes_cobro SET estado='PAGADO'", "SELECT * FROM pacientes",
                "SELECT pg_sleep(1) FROM report_income", "SELECT * INTO evil FROM report_income", "UNSUPPORTED",
                "SELECT * FROM report_income WHERE day BETWEEN '2090-01-01' AND '2090-01-31'"}) {
            givenSql(sql);
            graphQl.document(QUERY).variable("q", "Ingresos").execute().errors()
                    .expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST && e.getMessage().equals("Consulta de reporte no permitida")).verify();
            assertThat(snapshot()).isEqualTo(snapshot);
        }
    }

    private String snapshot() {
        StringBuilder result = new StringBuilder();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            result.append(table).append(jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]') FROM " + table + " t", String.class));
        }
        result.append(jdbc.queryForList("SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename"));
        return result.toString();
    }

    @Test
    void databaseRoleCannotReadBaseTablesWriteOrAdvanceCountersEvenWithoutGuard() throws Exception {
        String before = snapshot();
        try (var c = DriverManager.getConnection(reportingUrl(), ROLE, PASSWORD);
             var s = c.createStatement()) {
            assertThat(s.executeQuery("SELECT * FROM report_services").next()).isTrue();
            for (String sql : new String[]{"SELECT * FROM pacientes", "UPDATE ordenes_cobro SET estado='PAGADO'",
                    "DELETE FROM movimientos_caja", "INSERT INTO report_services(code) VALUES ('x')",
                    "SELECT nextval('recibo_numero_seq')", "CREATE TABLE public.report_evil(id int)"}) {
                assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOf(java.sql.SQLException.class);
            }
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(queryPort.execute("SELECT * FROM report_services").rows()).isNotEmpty();
    }

    @Test
    void everyDocumentedSelectShapeRunsAgainstTheRestrictedRoleWithoutMutation() {
        String before = snapshot();
        for (String sql : new String[]{
                "SELECT * FROM report_income",
                "SELECT day, category, payment_method, amount_bs, movement_count FROM report_income",
                "SELECT status, SUM(total_bs), AVG(balance_bs), MIN(total_bs), MAX(total_bs), COUNT(*) FROM report_orders GROUP BY status ORDER BY status DESC LIMIT 500",
                "SELECT code, price_bs FROM report_services WHERE price_bs >= 0 AND active = true ORDER BY price_bs ASC LIMIT 1",
                "SELECT * FROM report_income WHERE day >= '2026-01-01' AND category = 'COBRO_CUOTA'",
                "SELECT COUNT(*) FROM report_orders LIMIT 1"}) {
            assertThat(queryPort.execute(sql).columns()).isNotEmpty();
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void databaseLockWaitIsBoundedAndReturnsOnlyGenericError() throws Exception {
        String before = snapshot();
        var source = web.getBean(javax.sql.DataSource.class);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE servicios_odontologicos IN ACCESS EXCLUSIVE MODE");
            long start = System.nanoTime();
            assertThatThrownBy(() -> queryPort.execute("SELECT * FROM report_services"))
                    .isInstanceOf(BusinessRuleException.class).hasMessage("No se pudo ejecutar el reporte de forma segura");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(6));
            connection.rollback();
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void invalidQuestionsAndUnknownSqlErrorsAreSafe() {
        for (String q : new String[]{" ", "x".repeat(501)}) {
            graphQl.document(QUERY).variable("q", q).execute().errors()
                    .expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST && e.getMessage().equals("La pregunta debe tener entre 1 y 500 caracteres")).verify();
        }
        verify(model, never()).call(any(Prompt.class));
        // Valid grammar but an invalid date: JDBC details and SQL must not escape.
        givenSql("SELECT * FROM report_income WHERE day = 'not-a-date'");
        graphQl.document(QUERY).variable("q", "Ingresos").execute().errors()
                .expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST && e.getMessage().equals("No se pudo ejecutar el reporte de forma segura")).verify();
    }

    @Test
    void expiredExportIsTheSameGeneric404AsUnknownOrWrongOwner() throws Exception {
        String id = generate();
        doReturn(clock.instant().plusSeconds(600)).when(clock).instant();
        mvc.perform(get("/api/v1/reports/export/pdf").param("reporteId", id))
                .andExpect(status().isNotFound()).andExpect(content().string("Reporte no disponible"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void exportUnknownInvalidFormatMalformedIdAndWrongOwnerHaveFixedSemantics() throws Exception {
        String id = generate();
        mvc.perform(get("/api/v1/reports/export/csv").param("reporteId", id))
                .andExpect(status().isBadRequest()).andExpect(content().string("Formato de reporte no permitido"));
        for (String candidate : new String[]{"not-a-uuid", UUID.randomUUID().toString()}) {
            mvc.perform(get("/api/v1/reports/export/pdf").param("reporteId", candidate))
                    .andExpect(status().isNotFound()).andExpect(content().string("Reporte no disponible"));
        }
        mvc.perform(get("/api/v1/reports/export/pdf").param("reporteId", id)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("other").roles("ADMIN")))
                .andExpect(status().isNotFound()).andExpect(content().string("Reporte no disponible"));
        mvc.perform(get("/api/v1/reports/export/pdf"))
                .andExpect(status().isNotFound()).andExpect(content().string("Reporte no disponible"));
    }

    @Test
    @WithMockUser(roles = "CAJERO")
    void onlyAdminCanGenerateOrExport() throws Exception {
        graphQl.document(QUERY).variable("q", "Ingresos").execute().errors()
                .expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        mvc.perform(get("/api/v1/reports/export/pdf").param("reporteId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        verify(model, never()).call(any(Prompt.class));
    }
}
