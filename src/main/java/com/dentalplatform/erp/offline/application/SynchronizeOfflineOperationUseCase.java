package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

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
        var intent = reserve.reserve(request);
        if (!execution.supports(request.type()) || (intent.state() != OfflineOperationReceipt.State.PREPARED
                && intent.state() != OfflineOperationReceipt.State.BLOCKED)) return intent;
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
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if ("23505".equals(sql.getSQLState()) && sql.getMessage() != null
                        && sql.getMessage().contains("pacientes_ci_nit")) return "PATIENT_DUPLICATE";
                if ("22001".equals(sql.getSQLState()) || "23502".equals(sql.getSQLState())
                        || "23514".equals(sql.getSQLState())) return "INVALID_INPUT";
            }
            if (cause instanceof BusinessRuleException || cause instanceof IllegalArgumentException) return "INVALID_INPUT";
        }
        return "TECHNICAL_RETRY_REQUIRED";
    }
}
