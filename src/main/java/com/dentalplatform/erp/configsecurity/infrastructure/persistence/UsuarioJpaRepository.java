package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UsuarioJpaRepository extends JpaRepository<UsuarioJpaEntity, UUID> {

    List<UsuarioJpaEntity> findAllByOrderByUsernameAsc();

    Optional<UsuarioJpaEntity> findByKeycloakId(String keycloakId);

    Optional<UsuarioJpaEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    /** Locks only the user rows (not roles) in a fixed order, so concurrent callers cannot deadlock. */
    @Query(value = """
            SELECT u.* FROM usuarios u JOIN roles r ON r.id = u.rol_id
            WHERE r.codigo = 'ADMIN' AND u.activo = true
            ORDER BY u.id
            FOR UPDATE OF u""", nativeQuery = true)
    List<UsuarioJpaEntity> bloquearAdminsActivos();
}
