package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface OdontologoJpaRepository extends JpaRepository<OdontologoJpaEntity, UUID> {

    List<OdontologoJpaEntity> findAllByOrderByApellidosAscNombresAsc();

    List<OdontologoJpaEntity> findByActivoTrueOrderByApellidosAscNombresAsc();

    boolean existsByCi(String ci);

    boolean existsByMatriculaProfesional(String matriculaProfesional);

    boolean existsByCiAndIdNot(String ci, UUID id);

    boolean existsByMatriculaProfesionalAndIdNot(String matriculaProfesional, UUID id);

    @Query("select count(o) from OdontologoJpaEntity o join o.especialidades e "
            + "where o.activo = true and e.id = :especialidadId")
    long contarActivosPorEspecialidad(@Param("especialidadId") UUID especialidadId);
}
