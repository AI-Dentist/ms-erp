package com.dentalplatform.erp.financebilling.application.port;

import java.util.Optional;
import java.util.UUID;

public interface ReceiptArtifactOwnershipPort {
    void bloquearRecibo(UUID receiptId);
    Optional<String> referenciaReservada(UUID receiptId);
}
