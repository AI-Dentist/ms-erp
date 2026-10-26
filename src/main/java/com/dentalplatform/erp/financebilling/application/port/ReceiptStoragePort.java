package com.dentalplatform.erp.financebilling.application.port;

import java.util.UUID;

public interface ReceiptStoragePort {
    /** Store a stable reference, never an expiring download URL. */
    String guardar(UUID reciboId, byte[] pdf);

    /** Resolve only the server-held reference belonging to this receipt. */
    default String resolverDescarga(UUID reciboId, String reference) {
        if (reference == null) return null;
        throw new IllegalArgumentException("Referencia de recibo inválida");
    }
}
