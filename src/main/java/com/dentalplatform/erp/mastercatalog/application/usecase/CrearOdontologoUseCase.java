package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearOdontologoCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.OdontologoCreadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CrearOdontologoUseCase {

    private final OdontologoRepository odontologos;
    private final EspecialidadRepository especialidades;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CatalogEventPublisherPort eventos;
    private final Clock clock;

    public CrearOdontologoUseCase(OdontologoRepository odontologos, EspecialidadRepository especialidades,
                                  RegistrarAuditoriaUseCase auditoria, CatalogEventPublisherPort eventos,
                                  Clock clock) {
        this.odontologos = odontologos;
        this.especialidades = especialidades;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public Odontologo ejecutar(CrearOdontologoCommand command) {
        List<Especialidad> asignadas = resolverEspecialidades(command.especialidadesIds());
        Odontologo nuevo = Odontologo.crear(command.ci(), command.matriculaProfesional(), command.nombres(),
                command.apellidos(), command.telefono(), command.email(), command.porcentajeComision(), asignadas,
                OffsetDateTime.now(clock));
        if (odontologos.existeCi(nuevo.getCi())) {
            throw new BusinessRuleException("Ya existe un odontólogo con el CI " + nuevo.getCi());
        }
        if (odontologos.existeMatricula(nuevo.getMatriculaProfesional())) {
            throw new BusinessRuleException(
                    "Ya existe un odontólogo con la matrícula " + nuevo.getMatriculaProfesional());
        }
        Odontologo guardado = odontologos.guardar(nuevo);
        auditoria.registrar("ODONTOLOGO_CREADO", "odontologos", guardado.getId(),
                Map.of("matriculaProfesional", guardado.getMatriculaProfesional()));
        eventos.publicar(OdontologoCreadoEvent.de(guardado));
        return guardado;
    }

    private List<Especialidad> resolverEspecialidades(List<UUID> ids) {
        Set<UUID> unicos = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        List<Especialidad> encontradas = especialidades.buscarPorIds(unicos);
        Set<UUID> encontradasIds = new LinkedHashSet<>();
        encontradas.forEach(e -> encontradasIds.add(e.getId()));
        List<UUID> faltantes = unicos.stream().filter(id -> !encontradasIds.contains(id)).toList();
        if (!faltantes.isEmpty()) {
            throw new BusinessRuleException("No existen las especialidades: " + faltantes);
        }
        return encontradas;
    }
}
