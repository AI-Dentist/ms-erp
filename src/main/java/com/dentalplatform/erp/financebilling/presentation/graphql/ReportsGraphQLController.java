package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.report.ReportResponse;
import com.dentalplatform.erp.financebilling.application.usecase.GenerarReporteNaturalIaUseCase;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class ReportsGraphQLController {
    private final GenerarReporteNaturalIaUseCase reports;
    public ReportsGraphQLController(GenerarReporteNaturalIaUseCase reports) { this.reports = reports; }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReportResponse generarReporteInteligente(@Argument String pregunta) {
        return reports.ejecutar(pregunta);
    }
}
