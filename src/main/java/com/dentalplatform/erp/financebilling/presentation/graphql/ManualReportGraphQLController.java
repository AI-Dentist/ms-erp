package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.port.Ms3ManualReportPort;
import com.dentalplatform.erp.financebilling.application.report.ReportResponse;
import com.dentalplatform.erp.financebilling.application.report.manual.EntidadReporte;
import com.dentalplatform.erp.financebilling.application.report.manual.GenerarReporteManualService;
import com.dentalplatform.erp.financebilling.application.report.manual.ReporteManualInput;
import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

/** Manual report builder: entity metadata and generation. ADMIN only, like the export endpoint. */
@Controller
public class ManualReportGraphQLController {
    private static final ErrorClassification SERVICE_UNAVAILABLE = ErrorClassification.errorClassification("SERVICE_UNAVAILABLE");
    private final GenerarReporteManualService service;

    public ManualReportGraphQLController(GenerarReporteManualService service) { this.service = service; }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<EntidadReporte> listarEntidadesReporte() { return service.entidades(); }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReportResponse generarReporteManual(@Argument ReporteManualInput input) { return service.generar(input); }

    /** MS3 unreachable, unauthorized or malformed: no report is stored and no partial data is returned. */
    @GraphQlExceptionHandler
    public GraphQLError ms3NoDisponible(Ms3ManualReportPort.NoDisponible error, GraphqlErrorBuilder<?> builder) {
        return builder.errorType(SERVICE_UNAVAILABLE).message(error.getMessage()).build();
    }
}
