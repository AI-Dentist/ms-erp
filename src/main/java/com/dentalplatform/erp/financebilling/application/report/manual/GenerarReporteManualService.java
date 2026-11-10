package com.dentalplatform.erp.financebilling.application.report.manual;

import com.dentalplatform.erp.financebilling.application.port.ManualReportReadPort;
import com.dentalplatform.erp.financebilling.application.port.Ms3ManualReportPort;
import com.dentalplatform.erp.financebilling.application.report.ReportResponse;
import com.dentalplatform.erp.financebilling.application.report.ReportStore;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manual report builder: whitelisted entity, columns and filters, no LLM. The only write is the cached report in
 * {@link ReportStore}, so the existing export endpoint serves it unchanged. Rejected requests store nothing.
 */
@Service
public class GenerarReporteManualService {
    static final int MAX_FILAS = 500;
    private static final String EXPORT_BASE = "/api/v1/reports/export/";
    private static final ZoneId ZONE = ZoneId.of("America/La_Paz");
    private static final Duration MAX_PERIODO = Duration.ofDays(366);
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FECHA_TITULO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ManualReportReadPort local;
    private final ObjectProvider<Ms3ManualReportPort> ms3;
    private final ReportStore store;
    private final JsonMapper json;

    public GenerarReporteManualService(ManualReportReadPort local, ObjectProvider<Ms3ManualReportPort> ms3,
                                       ReportStore store, JsonMapper json) {
        this.local = local;
        this.ms3 = ms3;
        this.store = store;
        this.json = json;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<EntidadReporte> entidades() { return CatalogoReporteManual.entidades(); }

    @PreAuthorize("hasRole('ADMIN')")
    public ReportResponse generar(ReporteManualInput input) {
        if (input == null) { throw invalido("La solicitud de reporte es obligatoria"); }
        var entidad = CatalogoReporteManual.buscar(input.entidad())
                .orElseThrow(() -> invalido("Entidad de reporte no permitida"));
        List<ColumnaReporte> columnas = columnas(entidad, input.columnas());
        String estado = input.estado();
        if (estado != null && !entidad.filtrosEstado().contains(estado)) {
            throw invalido("Estado no permitido para " + entidad.clave());
        }
        OffsetDateTime inicio = input.fechaInicio(), fin = input.fechaFin();
        if ((inicio != null || fin != null) && !entidad.filtroFecha()) {
            throw invalido("La entidad " + entidad.clave() + " no admite filtro de fecha");
        }
        if (inicio != null && fin != null) {
            if (inicio.isAfter(fin)) { throw invalido("fechaInicio no puede ser posterior a fechaFin"); }
            if (Duration.between(inicio, fin).compareTo(MAX_PERIODO) > 0) {
                throw invalido("El período no puede exceder los 366 días");
            }
        }
        if (entidad.esMs3() && (inicio == null) != (fin == null)) {
            throw invalido("Para " + entidad.clave() + " indique fechaInicio y fechaFin juntas o ninguna");
        }

        String sql;
        List<List<Object>> filas;
        boolean truncadas;
        if (entidad.esMs3()) {
            var port = ms3.getIfAvailable();
            if (port == null) { throw new Ms3ManualReportPort.NoDisponible(); }
            var resultado = port.consultar(entidad.clave(), fecha(inicio), fecha(fin), estado);
            sql = "MS3 GET /api/agendamiento/reportes/" + entidad.clave();
            truncadas = resultado.filas().size() > MAX_FILAS || resultado.total() > MAX_FILAS;
            filas = new ArrayList<>();
            for (Map<String, Object> fila : resultado.filas().subList(0, Math.min(MAX_FILAS, resultado.filas().size()))) {
                filas.add(columnas.stream().map(c -> fila.get(c.clave())).toList());
            }
        } else {
            var consulta = local.consultar(entidad.clave(), columnas, inicio, fin, estado, MAX_FILAS + 1);
            sql = consulta.sql();
            truncadas = consulta.filas().size() > MAX_FILAS;
            filas = consulta.filas().subList(0, Math.min(MAX_FILAS, consulta.filas().size()));
        }

        List<String> etiquetas = columnas.stream().map(ColumnaReporte::etiqueta).toList();
        List<List<Object>> tipadas = filas.stream().map(f -> f.stream().map(GenerarReporteManualService::celda).toList()).toList();
        List<List<String>> texto = tipadas.stream().map(f -> f.stream().map(GenerarReporteManualService::texto).toList()).toList();
        String periodo = periodo(inicio, fin) + (estado == null ? "" : " · estado " + estado);
        String titulo = entidad.nombre() + " — " + periodo;
        UUID id = UUID.randomUUID();
        var response = new ReportResponse(id, titulo, sql, etiquetas, json.writeValueAsString(texto), "TABLA",
                EXPORT_BASE + id + "/xlsx", EXPORT_BASE + id + "/pdf", titulo, sql, texto,
                resumen(entidad, texto.size(), periodo, truncadas), truncadas);
        store.put(SecurityContextHolder.getContext().getAuthentication().getName(), response,
                new ReportResult(etiquetas, tipadas));
        return response;
    }

    private static List<ColumnaReporte> columnas(EntidadReporte entidad, List<String> claves) {
        if (claves == null || claves.isEmpty()) { throw invalido("Seleccione al menos una columna"); }
        var vistas = new HashSet<String>();
        var resultado = new ArrayList<ColumnaReporte>();
        for (String clave : claves) {
            var columna = entidad.columnas().stream().filter(c -> c.clave().equals(clave)).findFirst()
                    .orElseThrow(() -> invalido("Columna no permitida para " + entidad.clave()));
            if (!vistas.add(clave)) { throw invalido("Columna duplicada: " + clave); }
            resultado.add(columna);
        }
        return resultado;
    }

    private static String resumen(EntidadReporte entidad, int filas, String periodo, boolean truncadas) {
        String texto = filas + " registro" + (filas == 1 ? "" : "s") + " de " + entidad.nombre() + " (" + periodo + ").";
        if (truncadas) { texto += " Se muestran los primeros " + MAX_FILAS + "; hay más registros."; }
        if (entidad.clave().equals("pagos")) {
            texto += " Los montos QR (QR_PAGOFACIL) son nominales: la demo de PagoFácil cobra el monto /1000.";
        }
        return texto;
    }

    private static String periodo(OffsetDateTime inicio, OffsetDateTime fin) {
        if (inicio == null && fin == null) { return "sin filtro de fecha"; }
        if (fin == null) { return "desde " + FECHA_TITULO.format(fecha(inicio)); }
        if (inicio == null) { return "hasta " + FECHA_TITULO.format(fecha(fin)); }
        return "del " + FECHA_TITULO.format(fecha(inicio)) + " al " + FECHA_TITULO.format(fecha(fin));
    }

    private static LocalDate fecha(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZONE).toLocalDate();
    }

    /** Export cell: numbers and booleans stay typed, dates become La Paz text, anything else is text. */
    private static Object celda(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) { return value; }
        if (value instanceof OffsetDateTime dateTime) { return FECHA_HORA.format(dateTime.atZoneSameInstant(ZONE)); }
        return value.toString();
    }

    private static String texto(Object value) {
        if (value == null) { return null; }
        if (value instanceof BigDecimal decimal) { return decimal.toPlainString(); }
        if (value instanceof Boolean flag) { return flag ? "Sí" : "No"; }
        return value.toString();
    }

    private static BusinessRuleException invalido(String mensaje) { return new BusinessRuleException(mensaje); }
}
