package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.repository.ConsultorioRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class ConsultorioRepositoryAdapter implements ConsultorioRepository {

    private final ConsultorioJpaRepository repository;

    ConsultorioRepositoryAdapter(ConsultorioJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Consultorio> listar() {
        return repository.findAllByOrderByCodigoAsc().stream().map(ConsultorioRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Optional<Consultorio> buscarPorId(UUID id) {
        return repository.findById(id).map(ConsultorioRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existeCodigoDeOtro(String codigo, UUID id) {
        return repository.existsByCodigoAndIdNot(codigo, id);
    }

    @Override
    public boolean existeCodigo(String codigo) {
        return repository.existsByCodigo(codigo);
    }

    @Override
    public Consultorio guardar(Consultorio c) {
        return toDomain(repository.save(new ConsultorioJpaEntity(c.getId(), c.getCodigo(), c.getNombre(),
                c.getUbicacion(), c.getEstado(), c.getCreatedAt())));
    }

    private static Consultorio toDomain(ConsultorioJpaEntity e) {
        return new Consultorio(e.getId(), e.getCodigo(), e.getNombre(), e.getUbicacion(), e.getEstado(),
                e.getCreatedAt());
    }
}
