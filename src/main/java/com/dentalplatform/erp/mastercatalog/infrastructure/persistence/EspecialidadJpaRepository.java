package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface EspecialidadJpaRepository extends JpaRepository<EspecialidadJpaEntity, UUID> {

    List<EspecialidadJpaEntity> findByActivoTrueOrderByNombreAsc();

    boolean existsByCodigo(String codigo);

    boolean existsByCodigoAndIdNot(String codigo, UUID id);
}
