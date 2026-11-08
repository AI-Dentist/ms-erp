package com.dentalplatform.erp.financebilling.application.report;

import java.util.List;
import java.util.UUID;

/**
 * Generated report as exposed to the agent client (GraphQL {@code ReporteGenerativoPayload}).
 * <p>
 * S6 additive fields: {@code titulo}, {@code sqlGenerado} (alias for {@code consultaSqlEjecutada}),
 * {@code filas} (rows as string arrays for direct table rendering), {@code resumenAnalitico}.
 * Legacy fields {@code consultaSqlEjecutada} and {@code filasJson} are kept for backward compatibility.
 * {@code preguntaOriginal} is used only internally for the export letterhead.
 */
public record ReportResponse(UUID reporteId, String preguntaOriginal, String consultaSqlEjecutada,
                             List<String> columnas, String filasJson, String tipoGraficoSugerido,
                             String urlDescargaExcel, String urlDescargaPdf,
                             // S6 additions:
                             String titulo, String sqlGenerado, List<List<String>> filas,
                             String resumenAnalitico) { }
