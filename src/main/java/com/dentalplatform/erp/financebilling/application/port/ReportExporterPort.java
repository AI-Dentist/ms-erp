package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.application.report.ReportDocument;

public interface ReportExporterPort {
    String format();
    String contentType();
    byte[] export(ReportDocument document);
}
