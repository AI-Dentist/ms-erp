package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioActualizadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.repository.ConsultorioRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Logical deletion: the office moves to estado INACTIVO (there is no activo column). Repeating it is a no-op. */
@Service
public class DesactivarConsultorioUseCase {

    private final ConsultorioRepository consultorios;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;

    public DesactivarConsultorioUseCase(ConsultorioRepository consultorios, RegistrarAuditoriaUseCase auditoria,
                                        CatalogEventPublisherPort eventos) {
        this.consultorios = consultorios;
        this.auditoria = auditoria;
        this.eventos = eventos;
    }

    @Transactional
    public void ejecutar(UUID id) {
        Consultorio actual = consultorios.buscarPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Consultorio", id));
        if (!actual.isActivo()) {
            return;
        }
        Consultorio inactivo = consultorios.guardar(actual.desactivar());
        auditoria.registrar("CONSULTORIO_DESACTIVADO", "consultorios", inactivo.getId(),
                Map.of("codigo", inactivo.getCodigo()));
        eventos.publicar(ConsultorioActualizadoEvent.de(inactivo));
    }
}
