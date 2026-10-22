package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.domain.report.ReportResult;

public interface ReportExporterPort {
    String format();
    String contentType();
    byte[] export(ReportResult result);
}
