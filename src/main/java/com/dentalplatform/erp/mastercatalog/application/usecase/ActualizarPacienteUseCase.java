package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.ActualizarPacienteCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.PacienteActualizadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.repository.PacienteRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class ActualizarPacienteUseCase {

    private final PacienteRepository pacientes;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;
    private final Clock clock;

    public ActualizarPacienteUseCase(PacienteRepository pacientes, RegistrarAuditoriaUseCase auditoria,
                                     CatalogEventPublisherPort eventos, Clock clock) {
        this.pacientes = pacientes;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public Paciente ejecutar(ActualizarPacienteCommand command) {
        Paciente actual = pacientes.buscarPorId(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Paciente", command.id()));
        Paciente editado = actual.actualizar(command.ciNit(), command.nombres(), command.apellidos(),
                command.fechaNacimiento(), command.genero(), command.telefonoWhatsapp(), command.email(),
                command.direccion(), OffsetDateTime.now(clock));
        if (pacientes.existeCiNitDeOtro(editado.getCiNit(), editado.getId())) {
            throw new BusinessRuleException("Ya existe un paciente con el CI/NIT " + editado.getCiNit(),
                    BusinessRuleException.PATIENT_CI_NIT_DUPLICATE);
        }
        Paciente guardado = pacientes.guardar(editado);
        auditoria.registrar("PACIENTE_ACTUALIZADO", "pacientes", guardado.getId(),
                Map.of("ciNit", guardado.getCiNit()));
        eventos.publicar(PacienteActualizadoEvent.de(guardado));
        return guardado;
    }
}
