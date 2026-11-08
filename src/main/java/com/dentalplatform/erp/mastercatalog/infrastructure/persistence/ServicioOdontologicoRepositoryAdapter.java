package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class ServicioOdontologicoRepositoryAdapter implements ServicioOdontologicoRepository {

    private final ServicioOdontologicoJpaRepository repository;
    private final EspecialidadJpaRepository especialidades;

    ServicioOdontologicoRepositoryAdapter(ServicioOdontologicoJpaRepository repository,
                                          EspecialidadJpaRepository especialidades) {
        this.repository = repository;
        this.especialidades = especialidades;
    }

    @Override
    public List<ServicioOdontologico> listarActivos(UUID especialidadId) {
        List<ServicioOdontologicoJpaEntity> found = especialidadId == null
                ? repository.findByActivoTrueOrderByCodigoAsc()
                : repository.findByActivoTrueAndEspecialidad_IdOrderByCodigoAsc(especialidadId);
        return found.stream().map(ServicioOdontologicoRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Optional<ServicioOdontologico> buscarPorId(UUID id) {
        return repository.findById(id).map(ServicioOdontologicoRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existeCodigoDeOtro(String codigo, UUID id) {
        return repository.existsByCodigoAndIdNot(codigo, id);
    }

    @Override
    public long contarActivosPorEspecialidad(UUID especialidadId) {
        return repository.countByActivoTrueAndEspecialidad_Id(especialidadId);
    }

    @Override
    public boolean existeCodigo(String codigo) {
        return repository.existsByCodigo(codigo);
    }

    @Override
    public ServicioOdontologico guardar(ServicioOdontologico s) {
        EspecialidadJpaEntity especialidad = s.getEspecialidad() == null ? null
                : especialidades.getReferenceById(s.getEspecialidad().getId());
        return toDomain(repository.save(new ServicioOdontologicoJpaEntity(s.getId(), especialidad, s.getCodigo(),
                s.getNombre(), s.getDescripcion(), s.getPrecioBase().amount(), s.isActivo(), s.getCreatedAt(), s.getCategoria())));
    }

    private static ServicioOdontologico toDomain(ServicioOdontologicoJpaEntity e) {
        return new ServicioOdontologico(e.getId(),
                e.getEspecialidad() == null ? null : EspecialidadRepositoryAdapter.toDomain(e.getEspecialidad()),
                e.getCodigo(), e.getNombre(), e.getDescripcion(), Money.of(e.getPrecioBaseBs()), e.isActivo(),
                e.getCreatedAt(), e.getCategoria());
    }
}
