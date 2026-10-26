package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Logical deletion: the row is kept, {@code activo} becomes false. Repeating it on an inactive specialty is a no-op. */
@Service
public class DesactivarEspecialidadUseCase {

    private final EspecialidadRepository especialidades;
    private final ServicioOdontologicoRepository servicios;
    private final OdontologoRepository odontologos;
    private final RegistrarAuditoriaUseCase auditoria;

    public DesactivarEspecialidadUseCase(EspecialidadRepository especialidades,
                                         ServicioOdontologicoRepository servicios, OdontologoRepository odontologos,
                                         RegistrarAuditoriaUseCase auditoria) {
        this.especialidades = especialidades;
        this.servicios = servicios;
        this.odontologos = odontologos;
        this.auditoria = auditoria;
    }

    @Transactional
    public void ejecutar(UUID id) {
        Especialidad actual = especialidades.buscarPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Especialidad", id));
        if (!actual.isActivo()) {
            return;
        }
        Especialidad inactiva = especialidades.guardar(actual.desactivar(servicios.contarActivosPorEspecialidad(id),
                odontologos.contarActivosPorEspecialidad(id)));
        auditoria.registrar("ESPECIALIDAD_DESACTIVADA", "especialidades", inactiva.getId(),
                Map.of("codigo", inactiva.getCodigo()));
    }
}
