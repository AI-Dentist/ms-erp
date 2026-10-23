package com.dentalplatform.erp.financebilling.application.report;

import java.util.List;
import java.util.UUID;

public record ReportResponse(UUID reporteId, String preguntaOriginal, String explicacionNatural,
                             String consultaSqlEjecutada, List<String> columnas, String filasJson,
                             String tipoGraficoSugerido, String urlDescargaExcel, String urlDescargaPdf) {
    /** Temporary source-compatible bridge removed by the public agent cutover. */
    public ReportResponse(UUID reporteId, String preguntaOriginal, String consultaSqlEjecutada,
                          List<String> columnas, String filasJson, String tipoGraficoSugerido,
                          String urlDescargaExcel, String urlDescargaPdf) {
        this(reporteId, preguntaOriginal, "Resultado agregado de las vistas de reportes; sin datos personales.",
                consultaSqlEjecutada, columnas, filasJson, tipoGraficoSugerido, urlDescargaExcel, urlDescargaPdf);
    }
}
