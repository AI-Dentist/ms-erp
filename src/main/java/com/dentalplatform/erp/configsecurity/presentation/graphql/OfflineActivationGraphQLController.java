package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.port.OfflineActivationPort.SignedDocument;
import com.dentalplatform.erp.configsecurity.application.usecase.ActivarJornadaOfflineUseCase;
import com.dentalplatform.erp.configsecurity.application.port.OfflineActivationPort.OfflineSigningUnavailableException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class OfflineActivationGraphQLController {
    private final ActivarJornadaOfflineUseCase activation;

    public OfflineActivationGraphQLController(ActivarJornadaOfflineUseCase activation) { this.activation = activation; }

    public record OfflineInput(UUID workspaceId, UUID deviceId, Integer durationSeconds) { }

    @MutationMapping
    public SignedDocument activarJornadaOffline(@Argument OfflineInput input) {
        return activation.ejecutar(input.workspaceId(), input.deviceId(), input.durationSeconds() == null ? 28800 : input.durationSeconds());
    }

    @GraphQlExceptionHandler(AccessDeniedException.class)
    public GraphQLError denied(AccessDeniedException exception, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env).errorType(ErrorType.FORBIDDEN).message("Acceso offline denegado").build();
    }

    @GraphQlExceptionHandler(OfflineSigningUnavailableException.class)
    public GraphQLError signingUnavailable(OfflineSigningUnavailableException exception, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env).errorType(ErrorType.INTERNAL_ERROR).message(exception.getMessage()).build();
    }
}
