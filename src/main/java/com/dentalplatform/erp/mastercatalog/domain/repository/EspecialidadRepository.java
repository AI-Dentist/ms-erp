package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EspecialidadRepository {

    List<Especialidad> listarActivas();

    Optional<Especialidad> buscarPorId(UUID id);

    List<Especialidad> buscarPorIds(Collection<UUID> ids);

    boolean existeCodigo(String codigo);

    Especialidad guardar(Especialidad especialidad);
}
