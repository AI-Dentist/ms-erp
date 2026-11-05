package com.dentalplatform.erp.financebilling.application.port;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The durable rejection is also the pending compensation outbox entry. */
public interface RechazoFacturacionPort {
    boolean existePorCierre(UUID consultaFinalizadaEventId);

    /** Returns false for an already recorded closure; never overwrites its original event. */
    boolean insertarSiAusente(Rechazo rechazo);

    record Rechazo(UUID eventId, UUID consultaId, UUID consultaFinalizadaEventId,
                   String motivoCodigo, String motivo, OffsetDateTime rechazadaEn) { }
}
