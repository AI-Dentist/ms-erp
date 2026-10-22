package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface EmpresaConfigJpaRepository extends JpaRepository<EmpresaConfigJpaEntity, UUID> {

    Optional<EmpresaConfigJpaEntity> findFirstByOrderByUpdatedAtDesc();
}
