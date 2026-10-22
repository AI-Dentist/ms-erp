package com.dentalplatform.erp.mastercatalog.domain.event;

import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;

import java.util.UUID;

/**
 * Wire payload of {@code catalogo.consultorio.creado}; the routing key is a method, not a component.
 */
public record ConsultorioCreadoEvent(UUID eventId, UUID consultorioId, String codigo, String nombre,
                                     String estado) implements DomainEvent {

    public static final String ROUTING_KEY = "catalogo.consultorio.creado";

    public static ConsultorioCreadoEvent de(Consultorio consultorio) {
        return new ConsultorioCreadoEvent(UUID.randomUUID(), consultorio.getId(), consultorio.getCodigo(),
                consultorio.getNombre(), consultorio.getEstado());
    }

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }
}
