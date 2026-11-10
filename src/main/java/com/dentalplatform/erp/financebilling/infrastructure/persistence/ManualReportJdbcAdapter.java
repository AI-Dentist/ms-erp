package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.application.port.ManualReportReadPort;
import com.dentalplatform.erp.financebilling.application.report.manual.CatalogoReporteManual;
import com.dentalplatform.erp.financebilling.application.report.manual.ColumnaReporte;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixed SQL per whitelisted entity and column. No client string is ever concatenated: the request only selects which
 * constant expressions appear and in which order, and every value (dates, estado, limit) is a bound parameter.
 * Projection expressions avoid commas and aliases so the exporters can still detect {@code *_bs} money columns.
 */
@Repository
public class ManualReportJdbcAdapter implements ManualReportReadPort {
    private static final ZoneId ZONE = ZoneId.of("America/La_Paz");
    private static final String ACTIVO = "IS NOT FALSE", INACTIVO = "IS FALSE";

    private record Fuente(String desde, String base, String fecha, boolean fechaEsDia, String orden,
                          Map<String, String> estados, Map<String, String> columnas) { }

    private static final Map<String, Fuente> FUENTES = Map.of(
            "pacientes", new Fuente("pacientes p", null, "p.created_at", false, "p.created_at, p.id",
                    Map.of("ACTIVO", "p.activo " + ACTIVO, "INACTIVO", "p.activo " + INACTIVO),
                    Map.ofEntries(Map.entry("id", "p.id"), Map.entry("ciNit", "p.ci_nit"),
                            Map.entry("nombres", "p.nombres"), Map.entry("apellidos", "p.apellidos"),
                            Map.entry("fechaNacimiento", "p.fecha_nacimiento"), Map.entry("genero", "p.genero"),
                            Map.entry("telefonoWhatsapp", "p.telefono_whatsapp"), Map.entry("email", "p.email"),
                            Map.entry("direccion", "p.direccion"), Map.entry("activo", "p.activo " + ACTIVO),
                            Map.entry("fechaRegistro", "p.created_at"))),
            "cuotas", new Fuente("cuotas_pago c JOIN ordenes_cobro o ON o.id = c.orden_cobro_id"
                    + " JOIN pacientes p ON p.id = o.paciente_id", null, "c.fecha_vencimiento", true,
                    "c.fecha_vencimiento, c.id",
                    Map.of("PENDIENTE", "c.estado = ?", "PAGADA", "c.estado = ?", "VENCIDA", "c.estado = ?",
                            "ANULADA", "c.estado = ?"),
                    Map.ofEntries(Map.entry("id", "c.id"), Map.entry("paciente", "p.nombres || ' ' || p.apellidos"),
                            Map.entry("ciNitPaciente", "p.ci_nit"), Map.entry("numeroCuota", "c.numero_cuota"),
                            Map.entry("montoCuotaBs", "c.monto_cuota_bs"),
                            Map.entry("fechaVencimiento", "c.fecha_vencimiento"), Map.entry("estado", "c.estado"),
                            Map.entry("fechaPago", "c.fecha_pago"), Map.entry("modalidadPago", "o.modalidad_pago"),
                            Map.entry("totalOrdenBs", "o.monto_total_bs"),
                            Map.entry("saldoOrdenBs", "o.saldo_pendiente_bs"), Map.entry("estadoOrden", "o.estado"))),
            "pagos", new Fuente("movimientos_caja m LEFT JOIN recibos r ON r.movimiento_caja_id = m.id"
                    + " LEFT JOIN usuarios u ON u.id = m.usuario_cajero_id", "m.tipo_movimiento = 'INGRESO'",
                    "m.fecha_movimiento", false, "m.fecha_movimiento, m.id", Map.of(),
                    Map.ofEntries(Map.entry("id", "m.id"), Map.entry("fechaHora", "m.fecha_movimiento"),
                            Map.entry("metodoPago", "m.metodo_pago"), Map.entry("categoria", "m.categoria"),
                            Map.entry("montoBs", "m.monto_bs"),
                            Map.entry("montoNominal", "m.metodo_pago = 'QR_PAGOFACIL'"),
                            Map.entry("numeroRecibo", "r.numero_recibo"), Map.entry("ciNitCliente", "r.ci_nit_cliente"),
                            Map.entry("razonSocialCliente", "r.razon_social_cliente"), Map.entry("concepto", "r.concepto"),
                            Map.entry("descripcion", "m.descripcion"), Map.entry("cajero", "u.nombre_completo"))),
            "egresos", new Fuente("movimientos_caja m LEFT JOIN usuarios u ON u.id = m.usuario_cajero_id",
                    "m.tipo_movimiento = 'EGRESO'", "m.fecha_movimiento", false, "m.fecha_movimiento, m.id", Map.of(),
                    Map.ofEntries(Map.entry("id", "m.id"), Map.entry("fechaHora", "m.fecha_movimiento"),
                            Map.entry("categoria", "m.categoria"), Map.entry("metodoPago", "m.metodo_pago"),
                            Map.entry("montoBs", "m.monto_bs"), Map.entry("descripcion", "m.descripcion"),
                            Map.entry("cajero", "u.nombre_completo"))),
            "servicios", new Fuente("servicios_odontologicos s LEFT JOIN especialidades e ON e.id = s.especialidad_id",
                    null, null, false, "s.codigo, s.id",
                    Map.of("ACTIVO", "s.activo " + ACTIVO, "INACTIVO", "s.activo " + INACTIVO),
                    Map.ofEntries(Map.entry("id", "s.id"), Map.entry("codigo", "s.codigo"), Map.entry("nombre", "s.nombre"),
                            Map.entry("categoria", "s.categoria"), Map.entry("especialidad", "e.nombre"),
                            Map.entry("descripcion", "s.descripcion"), Map.entry("precioBaseBs", "s.precio_base_bs"),
                            Map.entry("activo", "s.activo " + ACTIVO))));

    private final JdbcTemplate jdbc;

    public ManualReportJdbcAdapter(DataSource dataSource) {
        // Every MS1 catalog column must have a fixed expression: fail at startup, never at request time.
        for (var entidad : CatalogoReporteManual.entidades()) {
            if (entidad.esMs3()) { continue; }
            var fuente = FUENTES.get(entidad.clave());
            if (fuente == null || !fuente.columnas().keySet().equals(
                    entidad.columnas().stream().map(ColumnaReporte::clave).collect(java.util.stream.Collectors.toSet()))
                    || !fuente.estados().keySet().equals(java.util.Set.copyOf(entidad.filtrosEstado()))
                    || (fuente.fecha() != null) != entidad.filtroFecha()) {
                throw new IllegalStateException("Manual report source out of sync with the catalog: " + entidad.clave());
            }
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(5);
    }

    @Override
    @Transactional(readOnly = true)
    public Consulta consultar(String entidad, List<ColumnaReporte> columnas, OffsetDateTime inicio, OffsetDateTime fin,
                              String estado, int limite) {
        var fuente = FUENTES.get(entidad);
        if (fuente == null) { throw new IllegalArgumentException("Not an MS1 manual report entity"); }
        var condiciones = new ArrayList<String>();
        var parametros = new ArrayList<Object>();
        if (fuente.base() != null) { condiciones.add(fuente.base()); }
        if (inicio != null) {
            condiciones.add(fuente.fecha() + " >= ?");
            parametros.add(fuente.fechaEsDia() ? dia(inicio) : inicio);
        }
        if (fin != null) {
            condiciones.add(fuente.fecha() + " <= ?");
            parametros.add(fuente.fechaEsDia() ? dia(fin) : fin);
        }
        if (estado != null) {
            String condicion = fuente.estados().get(estado);
            condiciones.add(condicion);
            if (condicion.contains("?")) { parametros.add(estado); }
        }
        String sql = "SELECT " + String.join(", ", columnas.stream().map(c -> fuente.columnas().get(c.clave())).toList())
                + " FROM " + fuente.desde()
                + (condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones))
                + " ORDER BY " + fuente.orden() + " LIMIT ?";
        parametros.add(limite);
        List<List<Object>> filas = jdbc.query(sql, (rs, n) -> fila(rs, columnas), parametros.toArray());
        return new Consulta(sql, filas);
    }

    private static List<Object> fila(ResultSet rs, List<ColumnaReporte> columnas) throws SQLException {
        var fila = new ArrayList<Object>(columnas.size());
        for (int i = 0; i < columnas.size(); i++) {
            int index = i + 1;
            fila.add(switch (columnas.get(i).tipo()) {
                case CatalogoReporteManual.MONEDA_BS -> rs.getBigDecimal(index);
                case CatalogoReporteManual.FECHA -> rs.getObject(index, LocalDate.class);
                case CatalogoReporteManual.FECHA_HORA -> rs.getObject(index, OffsetDateTime.class);
                case CatalogoReporteManual.NUMERO, CatalogoReporteManual.BOOLEANO -> rs.getObject(index);
                default -> rs.getString(index);
            });
        }
        return fila;
    }

    private static LocalDate dia(OffsetDateTime value) { return value.atZoneSameInstant(ZONE).toLocalDate(); }
}
