package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
class OdontologoRepositoryAdapter implements OdontologoRepository {

    private final OdontologoJpaRepository repository;
    private final EspecialidadJpaRepository especialidades;

    OdontologoRepositoryAdapter(OdontologoJpaRepository repository, EspecialidadJpaRepository especialidades) {
        this.repository = repository;
        this.especialidades = especialidades;
    }

    @Override
    public List<Odontologo> listar(boolean soloActivos) {
        List<OdontologoJpaEntity> found = soloActivos
                ? repository.findByActivoTrueOrderByApellidosAscNombresAsc()
                : repository.findAllByOrderByApellidosAscNombresAsc();
        return found.stream().map(OdontologoRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Optional<Odontologo> buscarPorId(UUID id) {
        return repository.findById(id).map(OdontologoRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Odontologo> buscarPorUsuarioId(UUID usuarioId) {
        return repository.findByUsuarioId(usuarioId).map(OdontologoRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Odontologo> bloquearPorId(UUID id) {
        return repository.bloquearPorId(id).map(OdontologoRepositoryAdapter::toDomain);
    }

    @Override
    public void vincularUsuarioId(UUID id, UUID usuarioId) {
        if (repository.vincularUsuarioId(id, usuarioId) != 1) {
            throw new BusinessRuleException("La identidad odontológica ya está vinculada");
        }
    }

    @Override
    public boolean existeCi(String ci) {
        return repository.existsByCi(ci);
    }

    @Override
    public boolean existeMatricula(String matriculaProfesional) {
        return repository.existsByMatriculaProfesional(matriculaProfesional);
    }

    @Override
    public boolean existeCiDeOtro(String ci, UUID id) {
        return repository.existsByCiAndIdNot(ci, id);
    }

    @Override
    public boolean existeMatriculaDeOtro(String matriculaProfesional, UUID id) {
        return repository.existsByMatriculaProfesionalAndIdNot(matriculaProfesional, id);
    }

    @Override
    public long contarActivosPorEspecialidad(UUID especialidadId) {
        return repository.contarActivosPorEspecialidad(especialidadId);
    }

    @Override
    public Odontologo guardar(Odontologo o) {
        Set<EspecialidadJpaEntity> refs = new LinkedHashSet<>();
        o.getEspecialidades().forEach(e -> refs.add(especialidades.getReferenceById(e.getId())));
        return toDomain(repository.save(new OdontologoJpaEntity(o.getId(), o.getUsuarioId(), o.getCi(),
                o.getMatriculaProfesional(), o.getNombres(), o.getApellidos(), o.getTelefono(), o.getEmail(),
                o.getPorcentajeComision(), o.isActivo(), refs, o.getCreatedAt())));
    }

    private static Odontologo toDomain(OdontologoJpaEntity e) {
        List<Especialidad> asignadas = e.getEspecialidades().stream()
                .map(EspecialidadRepositoryAdapter::toDomain)
                .sorted(Comparator.comparing(Especialidad::getNombre))
                .toList();
        return new Odontologo(e.getId(), e.getUsuarioId(), e.getCi(), e.getMatriculaProfesional(), e.getNombres(),
                e.getApellidos(), e.getTelefono(), e.getEmail(), e.getPorcentajeComision(), e.isActivo(), asignadas,
                e.getCreatedAt());
    }
}
