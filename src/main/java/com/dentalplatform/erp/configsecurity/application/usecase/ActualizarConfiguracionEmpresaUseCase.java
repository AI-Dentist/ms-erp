package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.command.ActualizarEmpresaCommand;
import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.configsecurity.domain.repository.EmpresaConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class ActualizarConfiguracionEmpresaUseCase {

    private final EmpresaConfigRepository repository;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public ActualizarConfiguracionEmpresaUseCase(EmpresaConfigRepository repository, RegistrarAuditoriaUseCase auditoria,
                                                 Clock clock) {
        this.repository = repository;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public EmpresaConfig ejecutar(ActualizarEmpresaCommand command) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        EmpresaConfig config = repository.obtener().orElseGet(() -> EmpresaConfig.nueva(now));
        config.actualizar(command.nombreComercial(), command.razonSocial(), command.nit(), command.direccion(),
                command.telefonoContacto(), command.emailContacto(), now);
        EmpresaConfig guardada = repository.guardar(config);
        auditoria.registrar("EMPRESA_CONFIG_ACTUALIZADA", "empresa_config", guardada.getId(),
                Map.of("nit", guardada.getNit(), "nombreComercial", guardada.getNombreComercial()));
        return guardada;
    }
}
