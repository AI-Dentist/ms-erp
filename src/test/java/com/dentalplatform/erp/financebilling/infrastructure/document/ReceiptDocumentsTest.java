package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceiptDocumentsTest {
    @TempDir Path directory;

    private Recibo receipt() {
        var movement = MovimientoCaja.crear(UUID.randomUUID(), UUID.randomUUID(), TipoMovimientoCaja.INGRESO,
                "COBRO_CUOTA", MetodoPago.EFECTIVO, Money.of("1400"), "Cobro cuota 1",
                OffsetDateTime.parse("2026-06-01T10:00:00-04:00"));
        return Recibo.emitir(movement, 42, "1234567 SC", "Cliente Prueba");
    }

    @Test
    void pdfContainsCompanyLegalReceiptAndFormattedBolivianos() throws Exception {
        var company = mock(ObtenerConfiguracionEmpresaUseCase.class);
        when(company.ejecutar()).thenReturn(new EmpresaConfig(UUID.randomUUID(), "Clínica de prueba",
                "Empresa de prueba SRL", "1023456029", "Santa Cruz", "+59170000000", null, null, "Bs",
                OffsetDateTime.now()));
        byte[] bytes = new PdfReceiptExporter(company).generar(receipt());
        try (var reader = new PdfReader(bytes)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Clínica de prueba", "Empresa de prueba SRL", "1023456029", "Santa Cruz",
                    "REC-2026-000042", "1234567 SC", "Cliente Prueba", "Cobro cuota 1", "Bs 1,400.00");
        }
    }

    @Test
    void localStorageReturnsFileUriAndWritesExactBytesUnderConfiguredDirectory() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bytes = {37, 80, 68, 70};
        String url = new LocalReceiptStorageAdapter(directory.toString()).guardar(id, bytes);
        assertThat(URI.create(url).getScheme()).isEqualTo("file");
        assertThat(Path.of(URI.create(url))).isEqualTo(directory.resolve(id + ".pdf"));
        assertThat(Files.readAllBytes(Path.of(URI.create(url)))).isEqualTo(bytes);
    }

    @Test
    void storageFailurePropagatesToOptionalArtifactBoundary() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.write(file, new byte[]{1});
        assertThatThrownBy(() -> new LocalReceiptStorageAdapter(file.toString()).guardar(UUID.randomUUID(), new byte[]{1}))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
