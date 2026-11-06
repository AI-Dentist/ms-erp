package com.dentalplatform.erp.offline.presentation;

import com.dentalplatform.erp.offline.application.ConfirmOfflineSyncUseCase;
import com.dentalplatform.erp.offline.application.PrepareOfflineSyncUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationStatusUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationRequest;
import com.dentalplatform.erp.offline.application.ReserveOfflineOperationUseCase;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.UUID;

@Controller
public class OfflineSyncGraphQLController {
    private final PrepareOfflineSyncUseCase prepare;
    private final ConfirmOfflineSyncUseCase confirm;
    private final OfflineOperationStatusUseCase status;
    private final ReserveOfflineOperationUseCase reserve;

    public OfflineSyncGraphQLController(PrepareOfflineSyncUseCase prepare, ConfirmOfflineSyncUseCase confirm,
                                        OfflineOperationStatusUseCase status, ReserveOfflineOperationUseCase reserve) {
        this.prepare = prepare;
        this.confirm = confirm;
        this.status = status;
        this.reserve = reserve;
    }

    public record PrepareInput(UUID workspaceId, UUID deviceId, UUID expectedActorId) { }

    @MutationMapping
    public PrepareOfflineSyncUseCase.Challenge prepararSyncOffline(@Argument PrepareInput input) {
        if (input == null) throw new OfflineSyncFailure("INVALID_INPUT");
        return prepare.prepare(input.workspaceId(), input.deviceId(), input.expectedActorId());
    }

    @MutationMapping
    public ConfirmOfflineSyncUseCase.ConfirmedSession confirmarSyncOffline(@Argument UUID sessionId) {
        return confirm.confirm(sessionId);
    }

    @MutationMapping
    public OfflineOperationReceipt sincronizarOperacionOffline(@Argument OfflineOperationRequest input) {
        return reserve.reserve(input);
    }

    @QueryMapping
    public OfflineOperationReceipt estadoOperacionOffline(@Argument UUID sessionId, @Argument UUID operationId) {
        return status.status(sessionId, operationId);
    }

    @GraphQlExceptionHandler(OfflineSyncFailure.class)
    public GraphQLError failure(OfflineSyncFailure failure, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .errorType(failure.code().equals("INVALID_INPUT") ? ErrorType.BAD_REQUEST : ErrorType.FORBIDDEN)
                .message("No se pudo preparar la sincronización offline")
                .extensions(Map.of("code", failure.code()))
                .build();
    }
}
