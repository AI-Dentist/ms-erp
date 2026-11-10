package com.dentalplatform.erp.financebilling.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * MS3 source of the manual report builder ({@code GET /api/agendamiento/reportes/{entidad}}), called with the
 * caller's own Bearer token. Rows carry the S3 fields plus {@code pacienteNombre}, {@code odontologoNombre} and
 * {@code consultorioNombre} resolved from the MS1 catalog. Any failure is {@link NoDisponible}: never a partial report.
 */
public interface Ms3ManualReportPort {
    Resultado consultar(String entidad, LocalDate desde, LocalDate hasta, String estado);

    record Resultado(List<Map<String, Object>> filas, long total) {
        public Resultado { filas = List.copyOf(filas); }
    }

    final class NoDisponible extends RuntimeException {
        public static final String MENSAJE = "SERVICE_UNAVAILABLE: No se pudo obtener el reporte de MS3";

        public NoDisponible() { super(MENSAJE); }
    }
}
