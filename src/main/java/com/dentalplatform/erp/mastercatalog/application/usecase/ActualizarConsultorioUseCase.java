package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.ActualizarConsultorioCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioActualizadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.repository.ConsultorioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ActualizarConsultorioUseCase {

    private final ConsultorioRepository consultorios;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;

    public ActualizarConsultorioUseCase(ConsultorioRepository consultorios, RegistrarAuditoriaUseCase auditoria,
                                        CatalogEventPublisherPort eventos) {
        this.consultorios = consultorios;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    @Transactional
    public Consultorio ejecutar(ActualizarConsultorioCommand command) {
        Consultorio actual = consultorios.buscarPorId(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Consultorio", command.id()));
        Consultorio editado = actual.actualizar(command.codigo(), command.nombre(), command.ubicacion(),
                command.estado());
        if (consultorios.existeCodigoDeOtro(editado.getCodigo(), editado.getId())) {
            throw new BusinessRuleException("Ya existe un consultorio con el código " + editado.getCodigo());
        }
        Consultorio guardado = consultorios.guardar(editado);
        auditoria.registrar("CONSULTORIO_ACTUALIZADO", "consultorios", guardado.getId(),
                Map.of("codigo", guardado.getCodigo(), "estado", guardado.getEstado()));
        eventos.publicar(ConsultorioActualizadoEvent.de(guardado));
        return guardado;
    }
}
