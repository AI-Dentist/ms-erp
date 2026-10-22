package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;

interface ReciboJpaRepository extends JpaRepository<ReciboJpaEntity, UUID> {
    @Query(value = "SELECT nextval('recibo_numero_seq')", nativeQuery = true)
    long siguienteNumero();
    Optional<ReciboJpaEntity> findByMovimientoCajaId(UUID movimientoId);

    @Query(value = "SELECT r.* FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = :cuotaId", nativeQuery = true)
    Optional<ReciboJpaEntity> buscarPorCuota(UUID cuotaId);
}
