package com.dentalplatform.erp.financebilling.infrastructure.document;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Local-only storage. The file URI is not an HTTP download endpoint or an S3 URL. */
@Component
public class LocalReceiptStorageAdapter implements ReceiptStoragePort {
    private final Path directory;

    public LocalReceiptStorageAdapter(@Value("${erp.receipts.storage-dir:${java.io.tmpdir}/erp-recibos}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public String guardar(UUID reciboId, byte[] pdf) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(reciboId + ".pdf");
            Files.write(file, pdf);
            return file.toUri().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store receipt PDF", e);
        }
    }
}
