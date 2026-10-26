package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.domain.report.ReportResult;

public interface ReportQueryPort {
    ReportResult execute(String sql);
}
