package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OdontologoJpaRepository extends JpaRepository<OdontologoJpaEntity, UUID> {

    List<OdontologoJpaEntity> findAllByOrderByApellidosAscNombresAsc();

    Optional<OdontologoJpaEntity> findByUsuarioId(UUID usuarioId);

    // Lock only the aggregate row, not eager specialty joins.
    @Query(value = "SELECT o.* FROM odontologos o WHERE o.id = :id FOR UPDATE OF o", nativeQuery = true)
    Optional<OdontologoJpaEntity> bloquearPorId(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE odontologos SET usuario_id = :usuarioId WHERE id = :id AND usuario_id IS NULL",
            nativeQuery = true)
    int vincularUsuarioId(@Param("id") UUID id, @Param("usuarioId") UUID usuarioId);

    List<OdontologoJpaEntity> findByActivoTrueOrderByApellidosAscNombresAsc();

    boolean existsByCi(String ci);

    boolean existsByMatriculaProfesional(String matriculaProfesional);

    boolean existsByCiAndIdNot(String ci, UUID id);

    boolean existsByMatriculaProfesionalAndIdNot(String matriculaProfesional, UUID id);

    @Query("select count(o) from OdontologoJpaEntity o join o.especialidades e "
            + "where o.activo = true and e.id = :especialidadId")
    long contarActivosPorEspecialidad(@Param("especialidadId") UUID especialidadId);
}
