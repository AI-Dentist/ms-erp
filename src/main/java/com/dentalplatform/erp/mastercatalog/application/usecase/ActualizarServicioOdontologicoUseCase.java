package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.ActualizarServicioOdontologicoCommand;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Updating the price never touches charge-order items: they keep the unit price captured when they were created. */
@Service
public class ActualizarServicioOdontologicoUseCase {

    private final ServicioOdontologicoRepository servicios;
    private final EspecialidadRepository especialidades;
    private final RegistrarAuditoriaUseCase auditoria;

    public ActualizarServicioOdontologicoUseCase(ServicioOdontologicoRepository servicios,
                                                 EspecialidadRepository especialidades,
                                                 RegistrarAuditoriaUseCase auditoria) {
        this.servicios = servicios;
        this.especialidades = especialidades;
        this.auditoria = auditoria;
    }

    @Transactional
    public ServicioOdontologico ejecutar(ActualizarServicioOdontologicoCommand command) {
        ServicioOdontologico actual = servicios.buscarPorId(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Servicio odontológico", command.id()));
        Especialidad especialidad = command.especialidadId() == null ? null
                : especialidades.buscarPorId(command.especialidadId()).orElseThrow(() -> new BusinessRuleException(
                        "No existe la especialidad " + command.especialidadId()));
        ServicioOdontologico editado = actual.actualizar(especialidad, command.codigo(), command.nombre(),
                command.descripcion(), command.precioBaseBs());
        if (servicios.existeCodigoDeOtro(editado.getCodigo(), editado.getId())) {
            throw new BusinessRuleException("Ya existe un servicio con el código " + editado.getCodigo());
        }
        ServicioOdontologico guardado = servicios.guardar(editado);
        auditoria.registrar("SERVICIO_ODONTOLOGICO_ACTUALIZADO", "servicios_odontologicos", guardado.getId(),
                Map.of("codigo", guardado.getCodigo(), "precioBaseBs", guardado.getPrecioBase().amount()));
        return guardado;
    }
}
