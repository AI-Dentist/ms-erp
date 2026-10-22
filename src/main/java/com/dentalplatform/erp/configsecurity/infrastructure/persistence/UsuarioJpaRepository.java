package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UsuarioJpaRepository extends JpaRepository<UsuarioJpaEntity, UUID> {

    List<UsuarioJpaEntity> findAllByOrderByUsernameAsc();

    Optional<UsuarioJpaEntity> findByKeycloakId(String keycloakId);

    Optional<UsuarioJpaEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
