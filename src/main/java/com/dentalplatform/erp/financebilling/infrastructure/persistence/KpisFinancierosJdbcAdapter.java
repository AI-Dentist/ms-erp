package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.application.port.KpisFinancierosReadPort;
import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class KpisFinancierosJdbcAdapter implements KpisFinancierosReadPort {

    private final JdbcTemplate jdbc;

    public KpisFinancierosJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public IngresosData consultarIngresos(OffsetDateTime inicio, OffsetDateTime fin) {
        Timestamp startTs = Timestamp.from(inicio.toInstant());
        Timestamp endTs = Timestamp.from(fin.toInstant());

        String summarySql = """
                SELECT
                    COALESCE(SUM(monto_bs), 0) AS total_bs,
                    COUNT(*) AS cantidad,
                    COALESCE(SUM(CASE WHEN metodo_pago = 'QR_PAGOFACIL' THEN monto_bs ELSE 0 END), 0) AS monto_qr_bs
                FROM movimientos_caja
                WHERE tipo_movimiento = 'INGRESO'
                  AND fecha_movimiento >= ?
                  AND fecha_movimiento <= ?
                """;

        IngresosSummary summary = jdbc.queryForObject(summarySql, (rs, rowNum) -> new IngresosSummary(
                scale(rs.getBigDecimal("total_bs")),
                rs.getInt("cantidad"),
                scale(rs.getBigDecimal("monto_qr_bs"))
        ), startTs, endTs);

        String seriesSql = """
                SELECT
                    (fecha_movimiento AT TIME ZONE 'America/La_Paz')::date AS dia,
                    COALESCE(SUM(monto_bs), 0) AS monto_bs
                FROM movimientos_caja
                WHERE tipo_movimiento = 'INGRESO'
                  AND fecha_movimiento >= ?
                  AND fecha_movimiento <= ?
                GROUP BY (fecha_movimiento AT TIME ZONE 'America/La_Paz')::date
                ORDER BY dia ASC
                """;

        List<KpiSerieDia> series = jdbc.query(seriesSql, (rs, rowNum) -> new KpiSerieDia(
                rs.getDate("dia").toLocalDate(),
                scale(rs.getBigDecimal("monto_bs"))
        ), startTs, endTs);

        return new IngresosData(
                summary != null ? summary.totalBs : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                summary != null ? summary.cantidad : 0,
                summary != null ? summary.montoQrBs : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                series
        );
    }

    private record IngresosSummary(BigDecimal totalBs, int cantidad, BigDecimal montoQrBs) {}

    @Override
    public MorosidadData consultarMorosidad(LocalDate startDate, LocalDate endDate, LocalDate evalDate) {
        Date startD = Date.valueOf(startDate);
        Date endD = Date.valueOf(endDate);
        Date evalD = Date.valueOf(evalDate);

        String summarySql = """
                SELECT
                    COUNT(*) AS cuotas_exigibles,
                    COUNT(CASE WHEN c.estado IN ('PENDIENTE', 'VENCIDA') AND c.fecha_vencimiento < ? THEN 1 END) AS cuotas_vencidas,
                    COALESCE(SUM(CASE WHEN c.estado IN ('PENDIENTE', 'VENCIDA') AND c.fecha_vencimiento < ? THEN c.monto_cuota_bs ELSE 0 END), 0) AS monto_vencido_bs
                FROM cuotas_pago c
                JOIN ordenes_cobro o ON o.id = c.orden_cobro_id
                WHERE c.estado <> 'ANULADA'
                  AND c.fecha_vencimiento >= ?
                  AND c.fecha_vencimiento <= ?
                """;

        MorosidadSummary summary = jdbc.queryForObject(summarySql, (rs, rowNum) -> new MorosidadSummary(
                rs.getInt("cuotas_exigibles"),
                rs.getInt("cuotas_vencidas"),
                scale(rs.getBigDecimal("monto_vencido_bs"))
        ), evalD, evalD, startD, endD);

        String detalleSql = """
                SELECT
                    c.id AS cuota_id,
                    c.orden_cobro_id,
                    o.paciente_id,
                    TRIM(CONCAT(p.nombres, ' ', p.apellidos)) AS paciente_nombre,
                    c.numero_cuota,
                    c.monto_cuota_bs,
                    c.fecha_vencimiento,
                    (? - c.fecha_vencimiento) AS dias_atraso
                FROM cuotas_pago c
                JOIN ordenes_cobro o ON o.id = c.orden_cobro_id
                JOIN pacientes p ON p.id = o.paciente_id
                WHERE c.estado IN ('PENDIENTE', 'VENCIDA')
                  AND c.fecha_vencimiento >= ?
                  AND c.fecha_vencimiento <= ?
                  AND c.fecha_vencimiento < ?
                ORDER BY dias_atraso DESC, c.id ASC
                """;

        List<KpiCuotaVencida> detalle = jdbc.query(detalleSql, (rs, rowNum) -> new KpiCuotaVencida(
                rs.getObject("cuota_id", UUID.class),
                rs.getObject("orden_cobro_id", UUID.class),
                rs.getObject("paciente_id", UUID.class),
                rs.getString("paciente_nombre"),
                rs.getInt("numero_cuota"),
                scale(rs.getBigDecimal("monto_cuota_bs")),
                rs.getDate("fecha_vencimiento").toLocalDate(),
                rs.getInt("dias_atraso")
        ), evalD, startD, endD, evalD);

        return new MorosidadData(
                summary != null ? summary.cuotasExigibles : 0,
                summary != null ? summary.cuotasVencidas : 0,
                summary != null ? summary.montoVencidoBs : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                detalle
        );
    }

    private record MorosidadSummary(int cuotasExigibles, int cuotasVencidas, BigDecimal montoVencidoBs) {}

    @Override
    public List<KpiServicioRanking> consultarServiciosMasSolicitados(OffsetDateTime inicio, OffsetDateTime fin, int limit) {
        Timestamp startTs = Timestamp.from(inicio.toInstant());
        Timestamp endTs = Timestamp.from(fin.toInstant());

        String sql = """
                SELECT
                    s.id AS servicio_id,
                    s.nombre AS servicio_nombre,
                    COALESCE(SUM(oi.cantidad), 0) AS cantidad_total,
                    COALESCE(SUM(oi.subtotal_bs), 0) AS monto_total_bs
                FROM orden_cobro_items oi
                JOIN ordenes_cobro o ON o.id = oi.orden_cobro_id
                JOIN servicios_odontologicos s ON s.id = oi.servicio_id
                WHERE o.estado <> 'ANULADO'
                  AND o.created_at >= ?
                  AND o.created_at <= ?
                GROUP BY s.id, s.nombre
                ORDER BY cantidad_total DESC, monto_total_bs DESC, s.id ASC
                LIMIT ?
                """;

        return jdbc.query(sql, (rs, rowNum) -> new KpiServicioRanking(
                rs.getObject("servicio_id", UUID.class),
                rs.getString("servicio_nombre"),
                rs.getInt("cantidad_total"),
                scale(rs.getBigDecimal("monto_total_bs"))
        ), startTs, endTs, limit);
    }

    @Override
    public List<KpiFlujoDia> consultarFlujoSerieDia(OffsetDateTime inicio, OffsetDateTime fin) {
        Timestamp startTs = Timestamp.from(inicio.toInstant());
        Timestamp endTs = Timestamp.from(fin.toInstant());

        String sql = """
                SELECT
                    (fecha_movimiento AT TIME ZONE 'America/La_Paz')::date AS dia,
                    COALESCE(SUM(CASE WHEN tipo_movimiento = 'INGRESO' THEN monto_bs ELSE 0 END), 0) AS ingresos_bs,
                    COALESCE(SUM(CASE WHEN tipo_movimiento = 'EGRESO' THEN monto_bs ELSE 0 END), 0) AS egresos_bs
                FROM movimientos_caja
                WHERE fecha_movimiento >= ?
                  AND fecha_movimiento <= ?
                GROUP BY (fecha_movimiento AT TIME ZONE 'America/La_Paz')::date
                ORDER BY dia ASC
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal ingresos = scale(rs.getBigDecimal("ingresos_bs"));
            BigDecimal egresos = scale(rs.getBigDecimal("egresos_bs"));
            BigDecimal saldo = ingresos.subtract(egresos);
            return new KpiFlujoDia(
                    rs.getDate("dia").toLocalDate(),
                    ingresos,
                    egresos,
                    saldo
            );
        }, startTs, endTs);
    }

    @Override
    public List<CobranzaMetodoRow> consultarCobranzaPorMetodo(OffsetDateTime inicio, OffsetDateTime fin) {
        Timestamp startTs = Timestamp.from(inicio.toInstant());
        Timestamp endTs = Timestamp.from(fin.toInstant());

        String sql = """
                SELECT
                    metodo_pago,
                    COUNT(*) AS cantidad,
                    COALESCE(SUM(monto_bs), 0) AS monto_total_bs
                FROM movimientos_caja
                WHERE tipo_movimiento = 'INGRESO'
                  AND fecha_movimiento >= ?
                  AND fecha_movimiento <= ?
                GROUP BY metodo_pago
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            String methodStr = rs.getString("metodo_pago");
            MetodoPago mp = null;
            try {
                mp = MetodoPago.valueOf(methodStr);
            } catch (Exception ignored) {}
            return new CobranzaMetodoRow(
                    mp,
                    rs.getInt("cantidad"),
                    scale(rs.getBigDecimal("monto_total_bs"))
            );
        }, startTs, endTs);
    }
}
