package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.usecase.ObtenerKpisFinancierosUseCase;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.time.OffsetDateTime;

@Controller
public class KpisFinancierosGraphQLController {

    private final ObtenerKpisFinancierosUseCase useCase;

    public KpisFinancierosGraphQLController(ObtenerKpisFinancierosUseCase useCase) {
        this.useCase = useCase;
    }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ObtenerKpisFinancierosUseCase.KpisFinancieros obtenerKpisFinancieros(@Argument OffsetDateTime fechaInicio,
                                                                               @Argument OffsetDateTime fechaFin) {
        return useCase.ejecutar(fechaInicio, fechaFin);
    }
}
