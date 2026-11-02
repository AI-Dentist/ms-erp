package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerMiUsuarioIdUseCase;
import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerMiUsuarioIdUseCase.JwtSubjectRequiredException;
import com.dentalplatform.erp.configsecurity.application.usecase.VincularIdentidadUsuarioUseCase;
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
public class UsuarioIdentityGraphQLController {
    private final ObtenerMiUsuarioIdUseCase obtener;
    private final VincularIdentidadUsuarioUseCase vincular;

    public UsuarioIdentityGraphQLController(ObtenerMiUsuarioIdUseCase obtener,
                                           VincularIdentidadUsuarioUseCase vincular) {
        this.obtener = obtener;
        this.vincular = vincular;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    public UUID obtenerMiUsuarioId() {
        return obtener.ejecutar();
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public UUID vincularIdentidadUsuario(@Argument VincularIdentidadUsuarioInput input) {
        return vincular.ejecutar(input.usuarioId(), input.keycloakSubject());
    }

    @GraphQlExceptionHandler(JwtSubjectRequiredException.class)
    public GraphQLError missingJwtSubject(JwtSubjectRequiredException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment).errorType(ErrorType.FORBIDDEN)
                .message(exception.getMessage()).build();
    }
}
