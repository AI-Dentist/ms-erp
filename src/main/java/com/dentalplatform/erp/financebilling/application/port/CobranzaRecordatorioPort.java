package com.dentalplatform.erp.financebilling.application.port;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Dedup ledger of collection reminders. */
public interface CobranzaRecordatorioPort {
    /**
     * Atomically claims the right to remind an installment: true only when no reminder was recorded for it inside
     * {@code ventana} before {@code ahora}. Concurrent callers for the same installment serialize.
     */
    boolean reservar(UUID cuotaId, UUID usuarioId, String solicitante, OffsetDateTime ahora, Duration ventana);

    /** Compensation when delivery fails, so the reminder can be retried. */
    void liberar(UUID cuotaId, OffsetDateTime ahora);
}
