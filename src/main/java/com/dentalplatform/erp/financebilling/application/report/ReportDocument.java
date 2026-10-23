package com.dentalplatform.erp.financebilling.application.report;

import com.dentalplatform.erp.financebilling.domain.report.ReportResult;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Everything an exporter needs to render a cached report. {@code montoColumnas} are zero-based Bs columns. */
public record ReportDocument(String pregunta, ZonedDateTime generadoEn, ReportResult resultado,
                             Set<Integer> montoColumnas) {
    public static final ZoneId ZONE = ZoneId.of("America/La_Paz");

    public ReportDocument {
        montoColumnas = Set.copyOf(montoColumnas);
    }

    public static ReportDocument desde(ReportStore.Entry entry) {
        return new ReportDocument(entry.response().preguntaOriginal(), entry.createdAt().atZone(ZONE), entry.result(),
                columnasMonto(entry.response().consultaSqlEjecutada(), entry.result().columns()));
    }

    /**
     * A projected column is money when it comes from an {@code *_bs} view column (also through SUM/AVG/MIN/MAX).
     * The guarded grammar has no aliases, so the SELECT list maps one-to-one to the result columns; PostgreSQL
     * labels aggregates just "sum"/"avg", which is why the SQL, not the label, is the source of truth.
     */
    static Set<Integer> columnasMonto(String sql, List<String> columns) {
        Set<Integer> result = new HashSet<>();
        String lower = sql == null ? "" : sql.toLowerCase();
        int select = lower.indexOf("select"), from = lower.indexOf(" from ");
        if (select != 0 || from < 0) { return result; }
        String projection = lower.substring("select".length(), from).trim();
        if (projection.equals("*")) {
            for (int i = 0; i < columns.size(); i++) { if (columns.get(i).toLowerCase().endsWith("_bs")) { result.add(i); } }
            return result;
        }
        String[] expressions = projection.split(",");
        for (int i = 0; i < expressions.length && i < columns.size(); i++) {
            String expression = expressions[i].trim();
            if (expression.contains("_bs") && !expression.startsWith("count")) { result.add(i); }
        }
        return result;
    }
}
