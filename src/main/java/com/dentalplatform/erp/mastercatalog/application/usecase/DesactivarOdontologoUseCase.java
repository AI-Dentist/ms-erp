package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.OdontologoActualizadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Logical deletion: the row is kept, {@code activo} becomes false. Repeating it on an inactive dentist is a no-op. */
@Service
public class DesactivarOdontologoUseCase {

    private final OdontologoRepository odontologos;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;

    public DesactivarOdontologoUseCase(OdontologoRepository odontologos, RegistrarAuditoriaUseCase auditoria,
                                       CatalogEventPublisherPort eventos) {
        this.odontologos = odontologos;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    @Transactional
    public void ejecutar(UUID id) {
        Odontologo actual = odontologos.buscarPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Odontólogo", id));
        if (!actual.isActivo()) {
            return;
        }
        Odontologo inactivo = odontologos.guardar(actual.desactivar());
        auditoria.registrar("ODONTOLOGO_DESACTIVADO", "odontologos", inactivo.getId(),
                Map.of("matriculaProfesional", inactivo.getMatriculaProfesional()));
        eventos.publicar(OdontologoActualizadoEvent.de(inactivo));
    }
}
