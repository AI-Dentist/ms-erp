package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.application.port.OrdenesAbiertasPacientePort;
import com.dentalplatform.erp.mastercatalog.domain.event.PacienteActualizadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.repository.PacienteRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Logical deletion: the row is kept, {@code activo} becomes false. Repeating it on an inactive patient is a no-op. */
@Service
public class DesactivarPacienteUseCase {

    private final PacienteRepository pacientes;
    private final OrdenesAbiertasPacientePort ordenesAbiertas;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;

    public DesactivarPacienteUseCase(PacienteRepository pacientes, OrdenesAbiertasPacientePort ordenesAbiertas,
                                     RegistrarAuditoriaUseCase auditoria, CatalogEventPublisherPort eventos) {
        this.pacientes = pacientes;
        this.ordenesAbiertas = ordenesAbiertas;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    @Transactional
    public void ejecutar(UUID id) {
        Paciente actual = pacientes.buscarPorId(id).orElseThrow(() -> new ResourceNotFoundException("Paciente", id));
        if (!actual.isActivo()) {
            return;
        }
        Paciente inactivo = pacientes.guardar(actual.desactivar(ordenesAbiertas.contar(id)));
        auditoria.registrar("PACIENTE_DESACTIVADO", "pacientes", inactivo.getId(),
                Map.of("ciNit", inactivo.getCiNit()));
        eventos.publicar(PacienteActualizadoEvent.de(inactivo));
    }
}
