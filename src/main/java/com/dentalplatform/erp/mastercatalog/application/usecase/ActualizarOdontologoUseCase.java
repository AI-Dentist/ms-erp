package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.ActualizarOdontologoCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.OdontologoActualizadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ActualizarOdontologoUseCase {

    private final OdontologoRepository odontologos;
    private final EspecialidadRepository especialidades;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;

    public ActualizarOdontologoUseCase(OdontologoRepository odontologos, EspecialidadRepository especialidades,
                                       RegistrarAuditoriaUseCase auditoria, CatalogEventPublisherPort eventos) {
        this.odontologos = odontologos;
        this.especialidades = especialidades;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    @Transactional
    public Odontologo ejecutar(ActualizarOdontologoCommand command) {
        Odontologo actual = odontologos.buscarPorId(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Odontólogo", command.id()));
        Odontologo editado = actual.actualizar(command.ci(), command.matriculaProfesional(), command.nombres(),
                command.apellidos(), command.telefono(), command.email(), command.porcentajeComision(),
                EspecialidadesAsignadas.resolver(especialidades, command.especialidadesIds()));
        if (odontologos.existeCiDeOtro(editado.getCi(), editado.getId())) {
            throw new BusinessRuleException("Ya existe un odontólogo con el CI " + editado.getCi());
        }
        if (odontologos.existeMatriculaDeOtro(editado.getMatriculaProfesional(), editado.getId())) {
            throw new BusinessRuleException(
                    "Ya existe un odontólogo con la matrícula " + editado.getMatriculaProfesional());
        }
        Odontologo guardado = odontologos.guardar(editado);
        auditoria.registrar("ODONTOLOGO_ACTUALIZADO", "odontologos", guardado.getId(),
                Map.of("matriculaProfesional", guardado.getMatriculaProfesional()));
        eventos.publicar(OdontologoActualizadoEvent.de(guardado));
        return guardado;
    }
}
