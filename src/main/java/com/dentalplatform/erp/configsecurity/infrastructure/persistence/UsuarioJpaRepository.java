package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UsuarioJpaRepository extends JpaRepository<UsuarioJpaEntity, UUID> {

    List<UsuarioJpaEntity> findAllByOrderByUsernameAsc();

    Optional<UsuarioJpaEntity> findByKeycloakId(String keycloakId);

    // Native single-table locking avoids locking eager role joins.
    @Query(value = "SELECT u.* FROM usuarios u WHERE u.id = :id FOR UPDATE OF u", nativeQuery = true)
    Optional<UsuarioJpaEntity> bloquearPorId(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE usuarios SET keycloak_id = :subject WHERE id = :id AND keycloak_id IS NULL",
            nativeQuery = true)
    int vincularKeycloakId(@Param("id") UUID id, @Param("subject") String subject);

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
