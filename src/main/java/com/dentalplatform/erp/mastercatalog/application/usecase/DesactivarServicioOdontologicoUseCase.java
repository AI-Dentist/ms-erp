package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Logical deletion: the row is kept, {@code activo} becomes false. Repeating it on an inactive service is a no-op. */
@Service
public class DesactivarServicioOdontologicoUseCase {

    private final ServicioOdontologicoRepository servicios;
    private final RegistrarAuditoriaUseCase auditoria;

    public DesactivarServicioOdontologicoUseCase(ServicioOdontologicoRepository servicios,
                                                 RegistrarAuditoriaUseCase auditoria) {
        this.servicios = servicios;
        this.auditoria = auditoria;
    }

    @Transactional
    public void ejecutar(UUID id) {
        ServicioOdontologico actual = servicios.buscarPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Servicio odontológico", id));
        if (!actual.isActivo()) {
            return;
        }
        ServicioOdontologico inactivo = servicios.guardar(actual.desactivar());
        auditoria.registrar("SERVICIO_ODONTOLOGICO_DESACTIVADO", "servicios_odontologicos", inactivo.getId(),
                Map.of("codigo", inactivo.getCodigo()));
    }
}
