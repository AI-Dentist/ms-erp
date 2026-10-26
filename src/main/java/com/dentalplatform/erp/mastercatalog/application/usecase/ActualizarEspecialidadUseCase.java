package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.ActualizarEspecialidadCommand;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ActualizarEspecialidadUseCase {

    private final EspecialidadRepository especialidades;
    private final RegistrarAuditoriaUseCase auditoria;

    public ActualizarEspecialidadUseCase(EspecialidadRepository especialidades, RegistrarAuditoriaUseCase auditoria) {
        this.especialidades = especialidades;
        this.auditoria = auditoria;
    }

    @Transactional
    public Especialidad ejecutar(ActualizarEspecialidadCommand command) {
        Especialidad actual = especialidades.buscarPorId(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Especialidad", command.id()));
        Especialidad editada = actual.actualizar(command.codigo(), command.nombre(), command.descripcion());
        if (especialidades.existeCodigoDeOtra(editada.getCodigo(), editada.getId())) {
            throw new BusinessRuleException("Ya existe una especialidad con el código " + editada.getCodigo());
        }
        Especialidad guardada = especialidades.guardar(editada);
        auditoria.registrar("ESPECIALIDAD_ACTUALIZADA", "especialidades", guardada.getId(),
                Map.of("codigo", guardada.getCodigo(), "nombre", guardada.getNombre()));
        return guardada;
    }
}
