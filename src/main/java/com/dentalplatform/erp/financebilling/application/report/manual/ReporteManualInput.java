package com.dentalplatform.erp.financebilling.application.report.manual;

import java.time.OffsetDateTime;
import java.util.List;

/** GraphQL {@code ReporteManualInput}. Every value is validated against the whitelist before use. */
public record ReporteManualInput(String entidad, List<String> columnas, OffsetDateTime fechaInicio,
                                 OffsetDateTime fechaFin, String estado) { }
