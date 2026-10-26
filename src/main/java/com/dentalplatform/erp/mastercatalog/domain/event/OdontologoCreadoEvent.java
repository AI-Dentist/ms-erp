package com.dentalplatform.erp.mastercatalog.domain.event;

import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;

import java.util.List;
import java.util.UUID;

/**
 * Wire payload of {@code catalogo.odontologo.creado}; the routing key is a method, not a component.
 */
public record OdontologoCreadoEvent(UUID eventId, UUID odontologoId, String nombreCompleto,
                                    String matriculaProfesional, List<String> especialidades) implements DomainEvent {

    public static final String ROUTING_KEY = "catalogo.odontologo.creado";

    public static OdontologoCreadoEvent de(Odontologo odontologo) {
        return new OdontologoCreadoEvent(UUID.randomUUID(), odontologo.getId(), odontologo.nombreCompleto(),
                odontologo.getMatriculaProfesional(),
                odontologo.getEspecialidades().stream().map(Especialidad::getNombre).toList());
    }

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }
}
