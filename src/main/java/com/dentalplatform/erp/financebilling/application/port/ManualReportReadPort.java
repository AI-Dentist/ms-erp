package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.application.report.manual.ColumnaReporte;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only access to the MS1 entities of the manual report builder. The adapter maps whitelisted column keys to
 * fixed SQL expressions and binds every value; {@code sql} is the parameterized statement actually executed.
 */
public interface ManualReportReadPort {
    Consulta consultar(String entidad, List<ColumnaReporte> columnas, OffsetDateTime inicio, OffsetDateTime fin,
                       String estado, int limite);

    /** Cells are typed by column: String, Number, Boolean, LocalDate or OffsetDateTime (nulls preserved). */
    record Consulta(String sql, List<List<Object>> filas) { }
}
