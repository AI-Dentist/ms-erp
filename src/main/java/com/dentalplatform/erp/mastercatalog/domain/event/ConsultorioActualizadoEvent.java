package com.dentalplatform.erp.mastercatalog.domain.event;

import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;

import java.util.UUID;

/**
 * Wire payload of {@code catalogo.consultorio.actualizado}: same fields as {@code creado}; deactivation is
 * reported as {@code estado = INACTIVO}.
 */
public record ConsultorioActualizadoEvent(UUID eventId, UUID consultorioId, String codigo, String nombre,
                                          String estado) implements DomainEvent {

    public static final String ROUTING_KEY = "catalogo.consultorio.actualizado";

    public static ConsultorioActualizadoEvent de(Consultorio consultorio) {
        return new ConsultorioActualizadoEvent(UUID.randomUUID(), consultorio.getId(), consultorio.getCodigo(),
                consultorio.getNombre(), consultorio.getEstado());
    }

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }
}
