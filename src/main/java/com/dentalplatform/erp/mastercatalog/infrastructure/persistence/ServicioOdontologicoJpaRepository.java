package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ServicioOdontologicoJpaRepository extends JpaRepository<ServicioOdontologicoJpaEntity, UUID> {

    List<ServicioOdontologicoJpaEntity> findByActivoTrueOrderByCodigoAsc();

    List<ServicioOdontologicoJpaEntity> findByActivoTrueAndEspecialidad_IdOrderByCodigoAsc(UUID especialidadId);

    boolean existsByCodigo(String codigo);

    boolean existsByCodigoAndIdNot(String codigo, UUID id);

    long countByActivoTrueAndEspecialidad_Id(UUID especialidadId);
}
