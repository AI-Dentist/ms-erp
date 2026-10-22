package com.dentalplatform.erp.financebilling.application.report;

import java.util.List;
import java.util.UUID;

public record ReportResponse(UUID reporteId, String preguntaOriginal, String explicacionNatural,
                             String consultaSqlEjecutada, List<String> columnas, String filasJson,
                             String tipoGraficoSugerido, String urlDescargaExcel, String urlDescargaPdf) { }
