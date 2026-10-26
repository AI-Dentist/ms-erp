package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearConsultorioCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioCreadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.repository.ConsultorioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class CrearConsultorioUseCase {

    private final ConsultorioRepository consultorios;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;
    private final Clock clock;

    public CrearConsultorioUseCase(ConsultorioRepository consultorios, RegistrarAuditoriaUseCase auditoria,
                                   CatalogEventPublisherPort eventos, Clock clock) {
        this.consultorios = consultorios;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public Consultorio ejecutar(CrearConsultorioCommand command) {
        Consultorio nuevo = Consultorio.crear(command.codigo(), command.nombre(), command.ubicacion(),
                OffsetDateTime.now(clock));
        if (consultorios.existeCodigo(nuevo.getCodigo())) {
            throw new BusinessRuleException("Ya existe un consultorio con el código " + nuevo.getCodigo());
        }
        Consultorio guardado = consultorios.guardar(nuevo);
        auditoria.registrar("CONSULTORIO_CREADO", "consultorios", guardado.getId(),
                Map.of("codigo", guardado.getCodigo()));
        eventos.publicar(ConsultorioCreadoEvent.de(guardado));
        return guardado;
    }
}
