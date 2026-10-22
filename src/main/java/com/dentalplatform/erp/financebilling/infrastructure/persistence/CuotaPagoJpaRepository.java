package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import org.springframework.data.jpa.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CuotaPagoJpaRepository extends JpaRepository<CuotaPagoJpaEntity, UUID> {
    List<CuotaPagoJpaEntity> findByOrdenCobroIdOrderByNumeroCuota(UUID ordenId);

    // Project only the order id: loading a cuota before acquiring the order lock would cache stale payment state.
    @Query("select c.ordenCobroId from CuotaPagoJpaEntity c where c.id = :id")
    Optional<UUID> obtenerOrdenId(UUID id);

    @Query("select c from CuotaPagoJpaEntity c, OrdenCobroJpaEntity o where c.ordenCobroId = o.id and o.pacienteId = :pacienteId and o.estado <> com.dentalplatform.erp.financebilling.domain.model.EstadoOrdenCobro.ANULADO and c.estado = com.dentalplatform.erp.financebilling.domain.model.EstadoCuota.PENDIENTE order by c.fechaVencimiento, c.id")
    List<CuotaPagoJpaEntity> pendientes(UUID pacienteId);
}
