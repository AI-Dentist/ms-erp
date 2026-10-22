package com.dentalplatform.erp.configsecurity.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable audit trail entry. {@code detalleJson} is a JSON document describing the change.
 */
public record AuditLog(UUID id, UUID usuarioId, String accion, String entidadAfectada, UUID entidadId,
                       String detalleJson, String ipOrigen, OffsetDateTime createdAt) {

    public static AuditLog registrar(UUID usuarioId, String accion, String entidadAfectada, UUID entidadId,
                                     String detalleJson, String ipOrigen, OffsetDateTime now) {
        return new AuditLog(UUID.randomUUID(), usuarioId, accion, entidadAfectada, entidadId, detalleJson, ipOrigen, now);
    }
}
