package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface OdontologoJpaRepository extends JpaRepository<OdontologoJpaEntity, UUID> {

    List<OdontologoJpaEntity> findAllByOrderByApellidosAscNombresAsc();

    List<OdontologoJpaEntity> findByActivoTrueOrderByApellidosAscNombresAsc();

    boolean existsByCi(String ci);

    boolean existsByMatriculaProfesional(String matriculaProfesional);
}
