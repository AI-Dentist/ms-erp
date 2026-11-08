package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.financebilling.application.report.ReportChartAdvisor;
import com.dentalplatform.erp.financebilling.application.report.ReportDocument;
import com.dentalplatform.erp.financebilling.application.report.ReportResponse;
import com.dentalplatform.erp.financebilling.application.report.ReportStore;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.financebilling.infrastructure.document.ExcelReportExporter;
import com.dentalplatform.erp.financebilling.infrastructure.document.PdfReportExporter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportStoreAndExportTest {
    @org.junit.jupiter.api.Nested
    @org.springframework.test.context.ActiveProfiles("dev")
    @org.springframework.security.test.context.support.WithMockUser(username = "admin", roles = "ADMIN")
    class FragmentCompatibility extends com.dentalplatform.erp.support.IntegrationTestBase {
    @Test
    void legacyFragmentsAndObjectKindRemainValid() {
        var legacy = graphQl.document("{ __type(name: \"AgenteInteraccionResponse\") { kind fields { name } } }").execute();
        var canonical = graphQl.document("{ __type(name: \"RespuestaAgenteErp\") { kind fields { name } } }").execute();
        legacy.path("__type.kind").entity(String.class).isEqualTo("OBJECT");
        canonical.path("__type.kind").entity(String.class).isEqualTo("OBJECT");
        assertThat(canonical.path("__type.fields[*].name").entityList(String.class).get())
                .containsExactlyElementsOf(legacy.path("__type.fields[*].name").entityList(String.class).get());
        graphQl.document("""
                mutation { ejecutarAgenteErp(instruccion: "cuéntame un chiste") {
                  __typename ...Legacy ... on AgenteInteraccionResponse { notificacionN8nEnviada }
                  toolsMcpInvocadas
                } }
                fragment Legacy on AgenteInteraccionResponse { respuestaNaturalAgente }
                """).execute().errors().verify()
                .path("ejecutarAgenteErp.__typename").entity(String.class).isEqualTo("AgenteInteraccionResponse")
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class).matches(t -> t.startsWith("[Respaldo determinístico — sin IA]"))
                .path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(false)
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).hasSize(0);
    }

    }

    private static final String SQL = "SELECT code, price_bs FROM report_services";
    private final ReportResult data = new ReportResult(List.of("code", "price_bs"),
            List.of(List.of("=HYPERLINK(\"evil\")", new BigDecimal("1400.5"))));
    private final ObtenerConfiguracionEmpresaUseCase company = mock(ObtenerConfiguracionEmpresaUseCase.class);
    private final ZonedDateTime generated = ZonedDateTime.parse("2026-06-01T10:30:00-04:00[America/La_Paz]");

    ReportStoreAndExportTest() {
        when(company.ejecutar()).thenReturn(new EmpresaConfig(UUID.randomUUID(), "Clínica Dental Sonrisa Cruceña",
                "Sonrisa Cruceña S.R.L.", "1023456029", "Av. Banzer 123, Santa Cruz", "+59133001122", null, null,
                "Bs", OffsetDateTime.now()));
    }

    private ReportResponse response() {
        return new ReportResponse(UUID.randomUUID(), "Servicios", SQL, data.columns(), "[]", "TABLA", "/xlsx", "/pdf",
                "Servicios", SQL, List.of(), "Resumen de prueba");
    }

    private ReportDocument document() {
        return new ReportDocument("Precios de servicios odontológicos", generated, data, Set.of(1));
    }

    @Test
    void cacheIsOwnerScopedBoundedAndExpiresAtExactTtl() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var store = new ReportStore(clock);
        var first = response();
        store.put("alice", first, data);
        assertThat(store.find("alice", first.reporteId())).isPresent();
        assertThat(store.find("alice", first.reporteId()).orElseThrow().createdAt()).isEqualTo(start);
        assertThat(store.find("bob", first.reporteId())).isEmpty();
        when(clock.instant()).thenReturn(start.plusSeconds(599));
        assertThat(store.find("alice", first.reporteId())).isPresent();
        when(clock.instant()).thenReturn(start.plusSeconds(600));
        assertThat(store.find("alice", first.reporteId())).isEmpty();
        when(clock.instant()).thenReturn(start);
        store.put("alice", first, data);
        ReportResponse last = first;
        for (int i = 0; i < 64; i++) { last = response(); store.put("bob", last, data); }
        assertThat(store.find("alice", first.reporteId())).isEmpty();
        assertThat(store.find("bob", last.reporteId())).isPresent();
    }

    @Test
    void excelHasLetterheadBoldHeaderNumericBsCellsAndNeverInterpretsTextAsFormula() throws Exception {
        byte[] bytes = new ExcelReportExporter(company).export(document());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Clínica Dental Sonrisa Cruceña");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Sonrisa Cruceña S.R.L.");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("NIT: 1023456029");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Av. Banzer 123, Santa Cruz");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Teléfono: +59133001122");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Reporte financiero");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Generado: 01/06/2026 10:30 (America/La_Paz)");
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Consulta: Precios de servicios odontológicos");
            var header = sheet.getRow(10);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("code");
            assertThat(book.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            var row = sheet.getRow(11);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=HYPERLINK(\"evil\")");
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(1400.5);
            assertThat(row.getCell(1).getCellStyle().getDataFormatString()).isEqualTo("\"Bs \"#,##0.00");
            assertThat(sheet.getColumnWidth(0)).isGreaterThan(12 * 256);
        }
    }

    @Test
    void excelNumericColumnsThatAreNotMoneyKeepTheGeneralFormat() throws Exception {
        var counts = new ReportResult(List.of("status", "count"), List.of(List.of("PENDIENTE", 3L)));
        byte[] bytes = new ExcelReportExporter(company).export(new ReportDocument("Órdenes", generated, counts, Set.of()));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var cell = book.getSheetAt(0).getRow(11).getCell(1);
            assertThat(cell.getNumericCellValue()).isEqualTo(3.0);
            assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("General");
        }
    }

    @Test
    void pdfHasLetterheadTitleDateQuestionAndBolivianosLikeTheReceipt() throws Exception {
        byte[] bytes = new PdfReportExporter(company).export(document());
        assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        try (var reader = new PdfReader(bytes)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Clínica Dental Sonrisa Cruceña", "Sonrisa Cruceña S.R.L.", "NIT: 1023456029",
                    "Av. Banzer 123, Santa Cruz", "Teléfono: +59133001122", "Reporte financiero",
                    "Generado: 01/06/2026 10:30", "Consulta: Precios de servicios odontológicos", "code", "price_bs",
                    "=HYPERLINK", "Bs 1,400.50");
        }
    }

    @Test
    void moneyColumnsAreDerivedFromTheGuardedSqlNotFromAggregateLabels() {
        var sums = new ReportResult(List.of("category", "sum"), List.of());
        var all = new ReportResult(List.of("day", "category", "payment_method", "amount_bs", "movement_count"), List.of());
        var entry = (java.util.function.BiFunction<String, ReportResult, ReportDocument>) (sql, result) -> {
            var store = new ReportStore(Clock.systemUTC());
            var id = UUID.randomUUID();
            store.put("u", new ReportResponse(id, "q", sql, result.columns(), "[]", "TABLA", "", "",
                    "q", sql, List.of(), ""), result);
            return ReportDocument.desde(store.find("u", id).orElseThrow());
        };
        assertThat(entry.apply("SELECT category, SUM(amount_bs) FROM report_income GROUP BY category", sums).montoColumnas())
                .containsExactly(1);
        assertThat(entry.apply("SELECT * FROM report_income", all).montoColumnas()).containsExactly(3);
        assertThat(entry.apply("SELECT status, COUNT(*), SUM(total_bs) FROM report_orders GROUP BY status",
                new ReportResult(List.of("status", "count", "sum"), List.of())).montoColumnas()).containsExactly(2);
        assertThat(entry.apply("SELECT code FROM report_services", new ReportResult(List.of("code"), List.of())).montoColumnas()).isEmpty();
    }

    private static ReportResult table(List<String> columns, Object[]... rows) {
        List<List<Object>> values = new ArrayList<>();
        for (Object[] row : rows) { values.add(Arrays.asList(row)); }
        return new ReportResult(columns, values);
    }

    @Test
    void chartSuggestionIsDeterministicFromTheResultShape() {
        assertThat(ReportChartAdvisor.sugerir(table(List.of("sum"), new Object[]{new BigDecimal("5")}))).isEqualTo("TABLA");
        assertThat(ReportChartAdvisor.sugerir(table(List.of("category", "sum")))).isEqualTo("TABLA");
        assertThat(ReportChartAdvisor.sugerir(table(List.of("code", "price_bs", "active"),
                new Object[]{"A", BigDecimal.ONE, true}))).isEqualTo("TABLA");
        assertThat(ReportChartAdvisor.sugerir(table(List.of("day", "sum"),
                new Object[]{"2026-01-01", BigDecimal.TEN}, new Object[]{"2026-01-02", BigDecimal.ONE}))).isEqualTo("LINEAS");
        assertThat(ReportChartAdvisor.sugerir(table(List.of("payment_method", "sum"),
                new Object[]{"EFECTIVO", new BigDecimal("30")}, new Object[]{"QR", new BigDecimal("70")}))).isEqualTo("TORTA");
        assertThat(ReportChartAdvisor.sugerir(table(List.of("payment_method", "sum"),
                new Object[]{"EFECTIVO", new BigDecimal("30")}, new Object[]{"QR", new BigDecimal("-70")}))).isEqualTo("BARRAS");
        assertThat(ReportChartAdvisor.sugerir(table(List.of("status", "count", "sum"),
                new Object[]{"A", 1L, BigDecimal.ONE}, new Object[]{"B", 2L, BigDecimal.TEN}))).isEqualTo("BARRAS");
        Object[][] many = new Object[7][];
        for (int i = 0; i < many.length; i++) { many[i] = new Object[]{"cat" + i, BigDecimal.valueOf(i + 1)}; }
        assertThat(ReportChartAdvisor.sugerir(table(List.of("category", "sum"), many))).isEqualTo("BARRAS");
        Object[][] huge = new Object[51][];
        for (int i = 0; i < huge.length; i++) { huge[i] = new Object[]{"cat" + i, BigDecimal.valueOf(i + 1)}; }
        assertThat(ReportChartAdvisor.sugerir(table(List.of("category", "sum"), huge))).isEqualTo("TABLA");
    }
}
