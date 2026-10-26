package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class EspecialidadRepositoryAdapter implements EspecialidadRepository {

    private final EspecialidadJpaRepository repository;

    EspecialidadRepositoryAdapter(EspecialidadJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Especialidad> listarActivas() {
        return repository.findByActivoTrueOrderByNombreAsc().stream().map(EspecialidadRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<Especialidad> buscarPorId(UUID id) {
        return repository.findById(id).map(EspecialidadRepositoryAdapter::toDomain);
    }

    @Override
    public List<Especialidad> buscarPorIds(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().map(EspecialidadRepositoryAdapter::toDomain).toList();
    }

    @Override
    public boolean existeCodigo(String codigo) {
        return repository.existsByCodigo(codigo);
    }

    @Override
    public boolean existeCodigoDeOtra(String codigo, UUID id) {
        return repository.existsByCodigoAndIdNot(codigo, id);
    }

    @Override
    public Especialidad guardar(Especialidad e) {
        return toDomain(repository.save(toEntity(e)));
    }

    static EspecialidadJpaEntity toEntity(Especialidad e) {
        return new EspecialidadJpaEntity(e.getId(), e.getCodigo(), e.getNombre(), e.getDescripcion(), e.isActivo());
    }

    static Especialidad toDomain(EspecialidadJpaEntity e) {
        return new Especialidad(e.getId(), e.getCodigo(), e.getNombre(), e.getDescripcion(), e.isActivo());
    }
}
