package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ConsultorioJpaRepository extends JpaRepository<ConsultorioJpaEntity, UUID> {

    List<ConsultorioJpaEntity> findAllByOrderByCodigoAsc();

    boolean existsByCodigo(String codigo);
}
