package com.dentalplatform.erp.financebilling.application.report;

import com.dentalplatform.erp.financebilling.domain.report.ReportResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic chart suggestion from the result shape only (no model involved):
 * <ul>
 *   <li>TABLA: no rows, a single column, more than 50 rows, no numeric column, or not exactly one label column;</li>
 *   <li>LINEAS: the label column holds ISO dates (a time series) and there are at least two points;</li>
 *   <li>TORTA: one numeric series with 2..6 non-negative categories adding up to a positive total (parts of a whole);</li>
 *   <li>BARRAS: any other label + numeric series comparison.</li>
 * </ul>
 */
public final class ReportChartAdvisor {
    public static final String BARRAS = "BARRAS", LINEAS = "LINEAS", TORTA = "TORTA", TABLA = "TABLA";
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private ReportChartAdvisor() { }

    public static String sugerir(ReportResult result) {
        int width = result.columns().size();
        int rows = result.rows().size();
        if (rows == 0 || width < 2 || rows > 50) { return TABLA; }
        List<Integer> numeric = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        for (int c = 0; c < width; c++) { (isNumeric(result, c) ? numeric : labels).add(c); }
        if (numeric.isEmpty() || labels.size() != 1) { return TABLA; }
        int label = labels.getFirst();
        boolean dates = result.rows().stream().allMatch(row -> row.get(label) instanceof String s && ISO_DATE.matcher(s).matches());
        if (dates) { return rows >= 2 ? LINEAS : BARRAS; }
        if (numeric.size() == 1 && rows >= 2 && rows <= 6) {
            BigDecimal total = BigDecimal.ZERO;
            boolean nonNegative = true;
            for (var row : result.rows()) {
                BigDecimal value = new BigDecimal(String.valueOf(row.get(numeric.getFirst())));
                nonNegative &= value.signum() >= 0;
                total = total.add(value);
            }
            if (nonNegative && total.signum() > 0) { return TORTA; }
        }
        return BARRAS;
    }

    private static boolean isNumeric(ReportResult result, int column) {
        boolean any = false;
        for (var row : result.rows()) {
            Object value = row.get(column);
            if (value == null) { continue; }
            if (!(value instanceof Number)) { return false; }
            any = true;
        }
        return any;
    }
}
