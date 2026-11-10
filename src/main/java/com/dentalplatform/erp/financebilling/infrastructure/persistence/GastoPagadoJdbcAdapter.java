package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.application.port.GastoPagadoReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class GastoPagadoJdbcAdapter implements GastoPagadoReadPort {
    private final JdbcTemplate jdbc;
    public GastoPagadoJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Evidencia> consultar(UUID pacienteId) {
        return jdbc.query("""
                SELECT o.id orden_id, o.estado orden_estado,
                       COALESCE(o.cantidad_cuotas = plan.cantidad AND o.monto_total_bs = plan.total
                           AND o.monto_total_bs::text NOT IN ('NaN','Infinity','-Infinity')
                           AND o.saldo_pendiente_bs::text NOT IN ('NaN','Infinity','-Infinity')
                           AND o.saldo_pendiente_bs = CASE WHEN o.estado = 'ANULADO' THEN 0 ELSE plan.pendiente END,
                           false) plan_consistente,
                       c.id cuota_id, c.estado cuota_estado,
                       CASE WHEN c.monto_cuota_bs::text IN ('NaN','Infinity','-Infinity') THEN NULL ELSE c.monto_cuota_bs END cuota_monto,
                       c.fecha_pago IS NOT NULL fecha_presente, m.id movimiento_id,
                       m.tipo_movimiento, m.categoria, m.metodo_pago,
                       CASE WHEN m.monto_bs::text IN ('NaN','Infinity','-Infinity') THEN NULL ELSE m.monto_bs END movimiento_monto,
                       r.id recibo_id,
                       CASE WHEN r.monto_total_bs::text IN ('NaN','Infinity','-Infinity') THEN NULL ELSE r.monto_total_bs END recibo_monto,
                       COALESCE(c.fecha_pago = m.fecha_movimiento AND m.fecha_movimiento = r.fecha_emision, false) fechas_coinciden
                FROM pacientes p
                LEFT JOIN ordenes_cobro o ON o.paciente_id = p.id
                LEFT JOIN LATERAL (
                    SELECT count(*) cantidad, sum(monto_cuota_bs) total,
                           COALESCE(sum(monto_cuota_bs) FILTER (WHERE estado <> 'PAGADA'), 0) pendiente
                    FROM cuotas_pago WHERE orden_cobro_id = o.id
                ) plan ON true
                LEFT JOIN cuotas_pago c ON c.orden_cobro_id = o.id
                LEFT JOIN movimientos_caja m ON m.cuota_id = c.id
                LEFT JOIN recibos r ON r.movimiento_caja_id = m.id
                WHERE p.id = ?
                """, (rs, n) -> new Evidencia(rs.getObject("orden_id", UUID.class), rs.getString("orden_estado"), rs.getBoolean("plan_consistente"),
                rs.getObject("cuota_id", UUID.class), rs.getString("cuota_estado"), rs.getBigDecimal("cuota_monto"),
                rs.getBoolean("fecha_presente"), rs.getObject("movimiento_id", UUID.class), rs.getString("tipo_movimiento"),
                rs.getString("categoria"), rs.getString("metodo_pago"), rs.getBigDecimal("movimiento_monto"),
                rs.getObject("recibo_id", UUID.class), rs.getBigDecimal("recibo_monto"), rs.getBoolean("fechas_coinciden")), pacienteId);
    }
}
