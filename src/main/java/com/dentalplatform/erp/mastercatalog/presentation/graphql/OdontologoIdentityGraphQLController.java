package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.usecase.ObtenerMiOdontologoIdUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ObtenerMiOdontologoIdUseCase.JwtSubjectRequiredException;
import com.dentalplatform.erp.mastercatalog.application.usecase.VincularIdentidadOdontologoUseCase;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class OdontologoIdentityGraphQLController {

    private final ObtenerMiOdontologoIdUseCase obtener;
    private final VincularIdentidadOdontologoUseCase vincular;

    public OdontologoIdentityGraphQLController(ObtenerMiOdontologoIdUseCase obtener,
                                              VincularIdentidadOdontologoUseCase vincular) {
        this.obtener = obtener;
        this.vincular = vincular;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ODONTOLOGO','ADMIN')")
    public UUID obtenerMiOdontologoId() {
        return obtener.ejecutar();
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public UUID vincularIdentidadOdontologo(@Argument VincularIdentidadOdontologoInput input) {
        return vincular.ejecutar(input.usuarioId(), input.odontologoId(), input.keycloakSubject());
    }

    @GraphQlExceptionHandler(JwtSubjectRequiredException.class)
    public GraphQLError missingJwtSubject(JwtSubjectRequiredException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment).errorType(ErrorType.FORBIDDEN)
                .message(exception.getMessage()).build();
    }
}
