package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.usecase.ObtenerGastoPagadoPacienteUseCase;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class GastoPagadoGraphQLController {
    private final ObtenerGastoPagadoPacienteUseCase useCase;
    public GastoPagadoGraphQLController(ObtenerGastoPagadoPacienteUseCase useCase) { this.useCase = useCase; }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA')")
    public ObtenerGastoPagadoPacienteUseCase.Resultado obtenerGastoPagadoPaciente(@Argument UUID pacienteId) {
        return useCase.ejecutar(pacienteId);
    }
}
