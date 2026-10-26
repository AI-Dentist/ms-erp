package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.financebilling.application.port.ReportExporterPort;
import com.dentalplatform.erp.financebilling.application.report.ReportDocument;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * Letterhead rows, bold column header and one row per record. Text is always written as a string cell, never as a
 * formula (a value such as {@code =HYPERLINK(...)} stays inert). Bs columns are numeric cells with a Bs number format.
 */
@Component
public class ExcelReportExporter implements ReportExporterPort {
    static final String BS_FORMAT = "\"Bs \"#,##0.00";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final ObtenerConfiguracionEmpresaUseCase empresa;

    public ExcelReportExporter(ObtenerConfiguracionEmpresaUseCase empresa) { this.empresa = empresa; }

    @Override public String format() { return "xlsx"; }
    @Override public String contentType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }

    @Override
    public byte[] export(ReportDocument document) {
        var config = empresa.ejecutar();
        var result = document.resultado();
        int width = result.columns().size();
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Reporte");
            var big = workbook.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            var bold = workbook.createFont();
            bold.setBold(true);
            CellStyle title = workbook.createCellStyle();
            title.setFont(big);
            CellStyle strong = workbook.createCellStyle();
            strong.setFont(bold);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat(BS_FORMAT));

            String[] letterhead = {config.getNombreComercial(), config.getRazonSocial(), "NIT: " + config.getNit(),
                    config.getDireccion(), "Teléfono: " + config.getTelefonoContacto(), "",
                    "Reporte financiero", "Generado: " + DATE.format(document.generadoEn()) + " (America/La_Paz)",
                    "Consulta: " + document.pregunta(), ""};
            for (int r = 0; r < letterhead.length; r++) {
                var cell = sheet.createRow(r).createCell(0);
                cell.setCellValue(letterhead[r]);
                if (r == 0) { cell.setCellStyle(title); } else if (r == 6) { cell.setCellStyle(strong); }
                if (width > 1 && !letterhead[r].isEmpty()) { sheet.addMergedRegion(new CellRangeAddress(r, r, 0, width - 1)); }
            }
            int headerRow = letterhead.length;
            var head = sheet.createRow(headerRow);
            for (int i = 0; i < width; i++) {
                var cell = head.createCell(i);
                cell.setCellValue(result.columns().get(i));
                cell.setCellStyle(header);
            }
            int index = headerRow + 1;
            for (var values : result.rows()) {
                var row = sheet.createRow(index++);
                for (int i = 0; i < values.size(); i++) {
                    var cell = row.createCell(i);
                    Object value = values.get(i);
                    if (value instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                        if (document.montoColumnas().contains(i)) { cell.setCellStyle(money); }
                    } else if (value instanceof Boolean b) {
                        cell.setCellValue(b ? "Sí" : "No");
                    } else if (value != null) {
                        cell.setCellValue(value.toString());
                    }
                }
            }
            sheet.createFreezePane(0, headerRow + 1);
            for (int i = 0; i < width; i++) { size(sheet, i, width); }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) { throw new BusinessRuleException("No se pudo exportar el reporte"); }
    }

    /** Autosize needs AWT font metrics; fall back to a fixed width where fonts are unavailable. */
    private static void size(Sheet sheet, int column, int width) {
        try { sheet.autoSizeColumn(column); }
        catch (Throwable fontsUnavailable) { sheet.setColumnWidth(column, 20 * 256); }
        // A single column also carries the (unmerged) letterhead; keep it readable and bounded.
        int current = sheet.getColumnWidth(column);
        int max = (width == 1 ? 60 : 255) * 256;
        sheet.setColumnWidth(column, Math.clamp(current, 12 * 256, max));
    }
}
