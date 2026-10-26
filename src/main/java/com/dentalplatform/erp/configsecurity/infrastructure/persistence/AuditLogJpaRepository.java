package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, UUID> {

    List<AuditLogJpaEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
