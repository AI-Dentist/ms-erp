package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.financebilling.application.report.ReportResponse;
import com.dentalplatform.erp.financebilling.application.report.ReportStore;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.financebilling.infrastructure.document.ExcelReportExporter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportStoreAndExportTest {
    private final ReportResult data = new ReportResult(List.of("code", "price_bs"),
            List.of(List.of("=HYPERLINK(\"evil\")", new BigDecimal("12.50"))));

    private ReportResponse response() {
        return new ReportResponse(UUID.randomUUID(), "Servicios", "Agregados", "SELECT * FROM report_services",
                data.columns(), "[]", "TABLA", "/xlsx", "/pdf");
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
    void excelNeverInterpretsTextAsFormulaAndKeepsAmountsNumeric() throws Exception {
        byte[] bytes = new ExcelReportExporter().export(data);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var row = book.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=HYPERLINK(\"evil\")");
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(12.5);
        }
    }
}
