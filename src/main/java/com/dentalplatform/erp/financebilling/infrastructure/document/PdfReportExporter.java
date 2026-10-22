package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReportExporterPort;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component
public class PdfReportExporter implements ReportExporterPort {
    @Override public String format() { return "pdf"; }
    @Override public String contentType() { return "application/pdf"; }

    @Override
    public byte[] export(ReportResult result) {
        try (var out = new ByteArrayOutputStream(); var document = new Document(PageSize.A4.rotate())) {
            PdfWriter.getInstance(document, out);
            document.open();
            var table = new PdfPTable(result.columns().size());
            table.setWidthPercentage(100);
            result.columns().forEach(column -> table.addCell(new Phrase(column)));
            table.setHeaderRows(1);
            result.rows().forEach(row -> row.forEach(value -> table.addCell(new Phrase(value == null ? "" : value.toString()))));
            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception e) { throw new BusinessRuleException("No se pudo exportar el reporte"); }
    }
}
