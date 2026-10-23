package com.dentalplatform.erp.mastercatalog.domain.event;

import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire payload of {@code catalogo.paciente.actualizado}: the {@code creado} fields plus {@code activo}. Published
 * for every successful update and deactivation.
 */
public record PacienteActualizadoEvent(UUID eventId, UUID pacienteId, String ciNit, String nombreCompleto,
                                       String telefonoWhatsapp, LocalDate fechaNacimiento,
                                       boolean activo) implements DomainEvent {

    public static final String ROUTING_KEY = "catalogo.paciente.actualizado";

    public static PacienteActualizadoEvent de(Paciente paciente) {
        return new PacienteActualizadoEvent(UUID.randomUUID(), paciente.getId(), paciente.getCiNit(),
                paciente.nombreCompleto(), paciente.getTelefonoWhatsapp(), paciente.getFechaNacimiento(),
                paciente.isActivo());
    }

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }
}
