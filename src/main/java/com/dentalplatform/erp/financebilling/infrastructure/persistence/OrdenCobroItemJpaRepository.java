package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface OrdenCobroItemJpaRepository extends JpaRepository<OrdenCobroItemJpaEntity, UUID> {
    List<OrdenCobroItemJpaEntity> findByOrdenCobroIdOrderById(UUID ordenId);
}
