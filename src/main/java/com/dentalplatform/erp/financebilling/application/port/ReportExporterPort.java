package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.application.report.ReportDocument;

public interface ReportExporterPort {
    String format();
    String contentType();
    byte[] export(ReportDocument document);
    default void exportTo(ReportDocument document, java.io.OutputStream output) throws java.io.IOException {
        output.write(export(document));
    }
}
