package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.ReportQueryPort;
import com.dentalplatform.erp.financebilling.application.port.SqlGeneratorPort;
import com.dentalplatform.erp.financebilling.application.report.ReportResponse;
import com.dentalplatform.erp.financebilling.application.report.ReportStore;
import com.dentalplatform.erp.financebilling.domain.report.SqlReadOnlyGuardrail;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Service
public class GenerarReporteNaturalIaUseCase {
    private final SqlGeneratorPort generator;
    private final ReportQueryPort query;
    private final ReportStore store;
    private final JsonMapper json;
    private final SqlReadOnlyGuardrail guard = new SqlReadOnlyGuardrail();

    public GenerarReporteNaturalIaUseCase(SqlGeneratorPort generator, ReportQueryPort query,
                                         ReportStore store, JsonMapper json) {
        this.generator = generator;
        this.query = query;
        this.store = store;
        this.json = json;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ReportResponse ejecutar(String pregunta) {
        if (pregunta == null || pregunta.isBlank() || pregunta.length() > 500) {
            throw new BusinessRuleException("La pregunta debe tener entre 1 y 500 caracteres");
        }
        String sql = guard.validate(generator.generate(pregunta));
        var result = query.execute(sql);
        UUID id = UUID.randomUUID();
        String base = "/api/v1/reports/export/";
        var response = new ReportResponse(id, pregunta,
                "Resultado agregado de las vistas de reportes; sin datos personales.", sql, result.columns(),
                json.writeValueAsString(result.rows()), "TABLA", base + id + "/xlsx", base + id + "/pdf");
        store.put(SecurityContextHolder.getContext().getAuthentication().getName(), response, result);
        return response;
    }
}
