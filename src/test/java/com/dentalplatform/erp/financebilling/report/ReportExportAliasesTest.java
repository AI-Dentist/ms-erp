package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.financebilling.application.report.*;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.financebilling.application.port.ReportExporterPort;
import com.dentalplatform.erp.financebilling.presentation.rest.ReportExportRestController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportExportAliasesTest {
    @Test
    void everyDownloadRouteStreamsTheSameDocumentWithOwnerIsolationAndFormatValidation() throws Exception {
        UUID id = UUID.randomUUID();
        var store = new ReportStore(Clock.systemUTC());
        var response = new ReportResponse(id, "test", "SELECT code FROM report_services LIMIT 500", List.of("code"),
                "[]", "BARRAS", "/xlsx", "/pdf", "test", "SELECT code FROM report_services LIMIT 500", List.of(), "0 filas");
        store.put("alice", response, new ReportResult(List.of("code"), List.of(List.of("Bs 12.50"))));
        var exporter = new ReportExporterPort() {
            public String format() { return "pdf"; }
            public String contentType() { return "application/pdf"; }
            public byte[] export(ReportDocument document) { throw new AssertionError("HTTP downloads must not buffer the document"); }
            public void exportTo(ReportDocument document, java.io.OutputStream output) throws java.io.IOException {
                output.write("Bs 12.50".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReportExportRestController(store, List.of(exporter))).build();
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", "n/a"));
            for (var request : List.of(get("/api/v1/reports/export/" + id + "/pdf"),
                    get("/api/v1/reports/export").param("reporteId", id.toString()).param("format", "pdf"),
                    post("/api/v1/reports/export").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reporteId\":\"" + id + "\",\"format\":\"pdf\"}"))) {
                mvc.perform(request).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                        .andExpect(header().string("Content-Disposition", "attachment; filename=\"reporte-" + id + ".pdf\""))
                        .andExpect(content().string("Bs 12.50"));
            }
            mvc.perform(get("/api/v1/reports/export").param("reporteId", id.toString()).param("format", "csv"))
                    .andExpect(status().isBadRequest()).andExpect(content().string("Formato de reporte no permitido"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("bob", "n/a"));
            mvc.perform(get("/api/v1/reports/export").param("reporteId", id.toString()).param("format", "pdf"))
                    .andExpect(status().isNotFound()).andExpect(content().string("Reporte no disponible"));
            assertThat(store.find("alice", id)).isPresent();
        } finally { SecurityContextHolder.clearContext(); }
    }
}
