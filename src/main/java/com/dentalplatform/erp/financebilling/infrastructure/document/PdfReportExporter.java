package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.financebilling.application.port.ReportExporterPort;
import com.dentalplatform.erp.financebilling.application.report.ReportDocument;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Same letterhead and Bs amount format ("Bs 1,400.00") as the receipt PDF. */
@Component
public class PdfReportExporter implements ReportExporterPort {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final ObtenerConfiguracionEmpresaUseCase empresa;

    public PdfReportExporter(ObtenerConfiguracionEmpresaUseCase empresa) { this.empresa = empresa; }

    @Override public String format() { return "pdf"; }
    @Override public String contentType() { return "application/pdf"; }

    @Override
    public byte[] export(ReportDocument document) {
        var output = new ByteArrayOutputStream();
        exportTo(document, output);
        return output.toByteArray();
    }

    @Override
    public void exportTo(ReportDocument document, java.io.OutputStream out) {
        var config = empresa.ejecutar();
        var result = document.resultado();
        int width = result.columns().size();
        var money = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        try (var pdf = new Document(width > 5 ? PageSize.A4.rotate() : PageSize.A4)) {
            PdfWriter.getInstance(pdf, out).setCloseStream(false);
            pdf.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
            pdf.add(new Paragraph(config.getNombreComercial(), title));
            pdf.add(new Paragraph(config.getRazonSocial(), normal));
            pdf.add(new Paragraph("NIT: " + config.getNit(), normal));
            pdf.add(new Paragraph(config.getDireccion(), normal));
            pdf.add(new Paragraph("Teléfono: " + config.getTelefonoContacto(), normal));
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Reporte financiero", bold));
            pdf.add(new Paragraph("Generado: " + DATE.format(document.generadoEn()) + " (America/La_Paz)", normal));
            pdf.add(new Paragraph("Consulta: " + document.pregunta(), normal));
            pdf.add(new Paragraph(" "));
            var table = new PdfPTable(width);
            table.setWidthPercentage(100);
            for (String column : result.columns()) {
                var cell = new PdfPCell(new Phrase(column, bold));
                cell.setBackgroundColor(Color.LIGHT_GRAY);
                table.addCell(cell);
            }
            table.setHeaderRows(1);
            for (var row : result.rows()) {
                for (int i = 0; i < width; i++) {
                    Object value = i < row.size() ? row.get(i) : null;
                    String text = value == null ? "" : value instanceof Number n && document.montoColumnas().contains(i)
                            ? "Bs " + money.format(new BigDecimal(n.toString())) : value.toString();
                    var cell = new PdfPCell(new Phrase(text, normal));
                    if (value instanceof Number) { cell.setHorizontalAlignment(Element.ALIGN_RIGHT); }
                    table.addCell(cell);
                }
            }
            pdf.add(table);
            pdf.close();

        } catch (Exception e) { throw new BusinessRuleException("No se pudo exportar el reporte"); }
    }
}
