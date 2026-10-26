package com.dentalplatform.erp.mastercatalog.domain.event;

import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire payload of {@code catalogo.paciente.creado}. Only the record components are serialized; the routing key is
 * a plain method, so it never reaches the message body.
 */
public record PacienteCreadoEvent(UUID eventId, UUID pacienteId, String ciNit, String nombreCompleto,
                                  String telefonoWhatsapp, LocalDate fechaNacimiento) implements DomainEvent {

    public static final String ROUTING_KEY = "catalogo.paciente.creado";

    public static PacienteCreadoEvent de(Paciente paciente) {
        return new PacienteCreadoEvent(UUID.randomUUID(), paciente.getId(), paciente.getCiNit(),
                paciente.nombreCompleto(), paciente.getTelefonoWhatsapp(), paciente.getFechaNacimiento());
    }

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }
}
