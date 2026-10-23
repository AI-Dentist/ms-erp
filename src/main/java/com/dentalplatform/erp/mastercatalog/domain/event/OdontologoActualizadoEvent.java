package com.dentalplatform.erp.mastercatalog.domain.event;

import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;

import java.util.List;
import java.util.UUID;

/**
 * Wire payload of {@code catalogo.odontologo.actualizado}: the {@code creado} fields plus {@code activo}. Published
 * for every successful update and deactivation.
 */
public record OdontologoActualizadoEvent(UUID eventId, UUID odontologoId, String nombreCompleto,
                                         String matriculaProfesional, List<String> especialidades,
                                         boolean activo) implements DomainEvent {

    public static final String ROUTING_KEY = "catalogo.odontologo.actualizado";

    public static OdontologoActualizadoEvent de(Odontologo odontologo) {
        return new OdontologoActualizadoEvent(UUID.randomUUID(), odontologo.getId(), odontologo.nombreCompleto(),
                odontologo.getMatriculaProfesional(),
                odontologo.getEspecialidades().stream().map(Especialidad::getNombre).toList(),
                odontologo.isActivo());
    }

    @Override
    public String routingKey() {
        return ROUTING_KEY;
    }
}
