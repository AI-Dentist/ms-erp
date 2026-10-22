package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface PacienteJpaRepository extends JpaRepository<PacienteJpaEntity, UUID> {

    List<PacienteJpaEntity> findByActivoTrueOrderByApellidosAscNombresAsc();

    /** {@code pattern} is the lower-cased text already wrapped in {@code %}. */
    @Query("""
            select p from PacienteJpaEntity p
            where p.activo = true
              and (lower(p.ciNit) like :pattern or lower(p.nombres) like :pattern or lower(p.apellidos) like :pattern)
            order by p.apellidos, p.nombres""")
    List<PacienteJpaEntity> buscarActivos(@Param("pattern") String pattern);

    boolean existsByCiNit(String ciNit);
}
