package com.dentalplatform.erp.financebilling.domain.report;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/** Immutable bounded snapshot. Null aggregate values are valid SQL results. */
public record ReportResult(List<String> columns, List<List<Object>> rows) {
    public ReportResult {
        columns = List.copyOf(columns);
        rows = rows.stream().map(row -> Collections.unmodifiableList(new ArrayList<>(row))).toList();
    }
}
