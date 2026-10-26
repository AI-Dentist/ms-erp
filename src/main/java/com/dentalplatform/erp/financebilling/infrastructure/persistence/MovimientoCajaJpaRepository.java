package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

interface MovimientoCajaJpaRepository extends JpaRepository<MovimientoCajaJpaEntity, UUID> {
    List<MovimientoCajaJpaEntity> findByFechaBetweenOrderByFechaAscIdAsc(OffsetDateTime inicio, OffsetDateTime fin);
}
