package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Service
public class SynchronizeOfflineOperationUseCase {
    private final ReserveOfflineOperationUseCase reserve;
    private final ExecuteOfflineOperationUseCase execution;

    public SynchronizeOfflineOperationUseCase(ReserveOfflineOperationUseCase reserve,
                                              ExecuteOfflineOperationUseCase execution) {
        this.reserve = reserve;
        this.execution = execution;
    }

    /** No enclosing transaction may retain session locks across the two sequential transactions. */
    @Transactional(propagation = Propagation.NEVER)
    public OfflineOperationReceipt synchronize(OfflineOperationRequest request) {
        // A type without a registered handler must be a typed failure before anything is reserved: returning the
        // still-PREPARED receipt would report a successful preparation the client can never execute.
        if (request == null || !execution.supports(request.type())) throw new OfflineSyncFailure("INVALID_INPUT");
        OfflineOperationReceipt intent;
        try {
            intent = reserve.reserve(request);
        } catch (OfflineSyncFailure denied) {
            // A denial raised while reserving still has to be recorded when the PREPARED row already committed;
            // recordDenied itself leaves nothing behind when no reservation exists.
            execution.recordDenied(request, denied);
            throw denied;
        }
        if (intent.state() == OfflineOperationReceipt.State.CONFLICT) {
            // A conflicting replay must be durable: otherwise status keeps answering a bare PREPARED with no
            // reason and a later denial could rewrite a row the client already sees as terminal.
            execution.recordDenied(request, new OfflineSyncFailure(intent.reasonCode()));
            return intent;
        }
        if (intent.state() != OfflineOperationReceipt.State.PREPARED
                && intent.state() != OfflineOperationReceipt.State.BLOCKED) return intent;
        try {
            return execution.execute(request);
        } catch (OfflineSyncFailure denied) {
            execution.recordDenied(request, denied);
            throw denied;
        } catch (RuntimeException failure) {
            if (failure instanceof OfflineOperationConflict conflict) {
                return execution.recover(request, conflict.reason(), conflict.state());
            }
            String reason = reason(failure);
            return execution.recover(request, reason, reason.equals("TECHNICAL_RETRY_REQUIRED")
                    ? OfflineOperationReceipt.State.PREPARED : OfflineOperationReceipt.State.CONFLICT);
        }
    }

    private static String reason(Throwable failure) {
        return OfflineFailureClassifier.reasonCode(failure);
    }

}
