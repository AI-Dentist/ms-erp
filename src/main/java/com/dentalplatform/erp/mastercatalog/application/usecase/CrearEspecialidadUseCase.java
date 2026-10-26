package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearEspecialidadCommand;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class CrearEspecialidadUseCase {

    private final EspecialidadRepository especialidades;
    private final RegistrarAuditoriaUseCase auditoria;

    public CrearEspecialidadUseCase(EspecialidadRepository especialidades, RegistrarAuditoriaUseCase auditoria) {
        this.especialidades = especialidades;
        this.auditoria = auditoria;
    }

    @Transactional
    public Especialidad ejecutar(CrearEspecialidadCommand command) {
        Especialidad nueva = Especialidad.crear(command.codigo(), command.nombre(), command.descripcion());
        if (especialidades.existeCodigo(nueva.getCodigo())) {
            throw new BusinessRuleException("Ya existe una especialidad con el código " + nueva.getCodigo());
        }
        Especialidad guardada = especialidades.guardar(nueva);
        auditoria.registrar("ESPECIALIDAD_CREADA", "especialidades", guardada.getId(),
                Map.of("codigo", guardada.getCodigo(), "nombre", guardada.getNombre()));
        return guardada;
    }
}
