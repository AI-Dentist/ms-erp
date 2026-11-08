package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearPacienteCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.PacienteCreadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.repository.PacienteRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class CrearPacienteUseCase {

    private final PacienteRepository pacientes;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;
    private final Clock clock;

    public CrearPacienteUseCase(PacienteRepository pacientes, RegistrarAuditoriaUseCase auditoria,
                                CatalogEventPublisherPort eventos, Clock clock) {
        this.pacientes = pacientes;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public Paciente ejecutar(CrearPacienteCommand command) {
        Paciente nuevo = Paciente.crear(command.ciNit(), command.nombres(), command.apellidos(),
                command.fechaNacimiento(), command.genero(), command.telefonoWhatsapp(), command.email(),
                command.direccion(), OffsetDateTime.now(clock));
        if (pacientes.existeCiNit(nuevo.getCiNit())) {
            throw new BusinessRuleException("Ya existe un paciente con el CI/NIT " + nuevo.getCiNit(),
                    BusinessRuleException.PATIENT_CI_NIT_DUPLICATE);
        }
        Paciente guardado = pacientes.guardar(nuevo);
        auditoria.registrar("PACIENTE_CREADO", "pacientes", guardado.getId(), Map.of("ciNit", guardado.getCiNit()));
        eventos.publicar(PacienteCreadoEvent.de(guardado));
        return guardado;
    }
}
