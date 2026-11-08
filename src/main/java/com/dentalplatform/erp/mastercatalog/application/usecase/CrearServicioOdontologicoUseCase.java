package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearServicioOdontologicoCommand;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class CrearServicioOdontologicoUseCase {

    private final ServicioOdontologicoRepository servicios;
    private final EspecialidadRepository especialidades;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public CrearServicioOdontologicoUseCase(ServicioOdontologicoRepository servicios,
                                            EspecialidadRepository especialidades,
                                            RegistrarAuditoriaUseCase auditoria, Clock clock) {
        this.servicios = servicios;
        this.especialidades = especialidades;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public ServicioOdontologico ejecutar(CrearServicioOdontologicoCommand command) {
        Especialidad especialidad = command.especialidadId() == null ? null
                : especialidades.buscarPorId(command.especialidadId()).orElseThrow(() -> new BusinessRuleException(
                        "No existe la especialidad " + command.especialidadId()));
        ServicioOdontologico nuevo = ServicioOdontologico.crear(especialidad, command.codigo(), command.nombre(),
                command.descripcion(), command.precioBaseBs(), OffsetDateTime.now(clock)).conCategoria(command.categoria());
        if (servicios.existeCodigo(nuevo.getCodigo())) {
            throw new BusinessRuleException("Ya existe un servicio con el código " + nuevo.getCodigo());
        }
        ServicioOdontologico guardado = servicios.guardar(nuevo);
        auditoria.registrar("SERVICIO_ODONTOLOGICO_CREADO", "servicios_odontologicos", guardado.getId(),
                Map.of("codigo", guardado.getCodigo(), "precioBaseBs", guardado.getPrecioBase().amount()));
        return guardado;
    }
}
