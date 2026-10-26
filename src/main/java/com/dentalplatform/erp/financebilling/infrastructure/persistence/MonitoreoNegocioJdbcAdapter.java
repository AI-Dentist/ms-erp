package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.application.port.MonitoreoNegocioPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Fixed parameterized read query; no model-generated SQL and no writes or status transitions. */
@Component
public class MonitoreoNegocioJdbcAdapter implements MonitoreoNegocioPort {
    private final JdbcTemplate jdbc;

    public MonitoreoNegocioJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<CuotaVencida> cuotasVencidas(LocalDate fechaCorte) {
        return jdbc.query("""
                SELECT p.id paciente_id, p.nombres || ' ' || p.apellidos nombre_completo,
                       p.ci_nit, p.telefono_whatsapp, o.id orden_id, c.id cuota_id,
                       c.numero_cuota, c.monto_cuota_bs, c.fecha_vencimiento
                FROM cuotas_pago c JOIN ordenes_cobro o ON o.id = c.orden_cobro_id
                JOIN pacientes p ON p.id = o.paciente_id
                WHERE c.estado = 'PENDIENTE' AND c.fecha_vencimiento < ?
                  AND o.estado IN ('PENDIENTE', 'PARCIAL')
                ORDER BY c.fecha_vencimiento, o.id, c.numero_cuota
                """, (rs, n) -> {
            LocalDate due = rs.getObject("fecha_vencimiento", LocalDate.class);
            return new CuotaVencida(rs.getObject("paciente_id", UUID.class), rs.getString("nombre_completo"),
                    rs.getString("ci_nit"), rs.getString("telefono_whatsapp"), rs.getObject("orden_id", UUID.class),
                    rs.getObject("cuota_id", UUID.class), rs.getInt("numero_cuota"), rs.getBigDecimal("monto_cuota_bs"),
                    due, ChronoUnit.DAYS.between(due, fechaCorte));
        }, fechaCorte);
    }
}
