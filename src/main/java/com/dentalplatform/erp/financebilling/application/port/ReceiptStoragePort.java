package com.dentalplatform.erp.financebilling.application.port;

import java.util.UUID;

public interface ReceiptStoragePort {
    /** Store a stable reference, never an expiring download URL. */
    String guardar(UUID reciboId, byte[] pdf);

    /**
     * Stable reference {@link #guardar} will return for this receipt, computed without storage I/O so ownership can
     * be committed before any upload. {@link #guardar} must write exactly this key and return this value.
     * Adapters that cannot name it fail closed: nothing is uploaded.
     */
    default String referencia(UUID reciboId) {
        throw new UnsupportedOperationException("El almacenamiento no declara la referencia del recibo");
    }

    /** Probe the same UUID-derived key; unsupported adapters cannot authorize a repair. */
    default boolean existe(UUID reciboId) { return false; }

    /** Resolve only the server-held reference belonging to this receipt. */
    default String resolverDescarga(UUID reciboId, String reference) {
        if (reference == null) return null;
        throw new IllegalArgumentException("Referencia de recibo inválida");
    }
}
