package com.dentalplatform.erp.financebilling.application.report;

import java.util.List;
import java.util.UUID;

/**
 * Generated report as exposed to the agent client (GraphQL {@code ReporteGenerativoPayload}); {@code preguntaOriginal}
 * is only used internally for the export letterhead.
 */
public record ReportResponse(UUID reporteId, String preguntaOriginal, String consultaSqlEjecutada,
                             List<String> columnas, String filasJson, String tipoGraficoSugerido,
                             String urlDescargaExcel, String urlDescargaPdf) { }
