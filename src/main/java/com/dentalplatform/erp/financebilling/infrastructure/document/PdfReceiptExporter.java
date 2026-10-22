package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

@Component
public class PdfReceiptExporter implements ReceiptPdfGeneratorPort {
    private final ObtenerConfiguracionEmpresaUseCase empresa;

    public PdfReceiptExporter(ObtenerConfiguracionEmpresaUseCase empresa) { this.empresa = empresa; }

    @Override
    public byte[] generar(Recibo recibo) {
        var config = empresa.ejecutar();
        var output = new ByteArrayOutputStream();
        var document = new Document();
        try {
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(new Paragraph(config.getNombreComercial()));
            document.add(new Paragraph(config.getRazonSocial()));
            document.add(new Paragraph("NIT: " + config.getNit()));
            document.add(new Paragraph(config.getDireccion()));
            document.add(new Paragraph("Teléfono: " + config.getTelefonoContacto()));
            document.add(new Paragraph("Recibo " + recibo.numeroRecibo()));
            document.add(new Paragraph("Fecha: " + recibo.fechaEmision()));
            document.add(new Paragraph("CI/NIT: " + recibo.ciNitCliente()));
            document.add(new Paragraph("Razón social: " + recibo.razonSocialCliente()));
            document.add(new Paragraph("Concepto: " + recibo.concepto()));
            var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
            document.add(new Paragraph("Monto: Bs " + format.format(recibo.montoTotal().amount())));
        } finally {
            document.close();
        }
        return output.toByteArray();
    }
}
