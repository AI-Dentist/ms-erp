package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReportExporterPort;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component
public class ExcelReportExporter implements ReportExporterPort {
    @Override public String format() { return "xlsx"; }
    @Override public String contentType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }

    @Override
    public byte[] export(ReportResult result) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Reporte");
            var header = sheet.createRow(0);
            for (int i = 0; i < result.columns().size(); i++) { header.createCell(i).setCellValue(result.columns().get(i)); }
            int index = 1;
            for (var values : result.rows()) {
                var row = sheet.createRow(index++);
                for (int i = 0; i < values.size(); i++) {
                    var cell = row.createCell(i);
                    Object value = values.get(i);
                    if (value instanceof Number n) { cell.setCellValue(n.doubleValue()); }
                    else if (value != null) { cell.setCellValue(value.toString()); }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) { throw new BusinessRuleException("No se pudo exportar el reporte"); }
    }
}
