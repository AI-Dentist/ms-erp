package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationConflict;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.regex.Pattern;

@Service
public class SynchronizeOfflineOperationUseCase {
    /**
     * PostgreSQL quotes the violated constraint name; classify by identifier, never by a localized message prefix.
     * The secondary quoted-token pattern keeps the classification when the server locale words the message
     * differently (for example with guillemets) instead of falling back to a generic technical retry.
     */
    private static final String DUPLICATE_CI_NIT = "pacientes_ci_nit";
    // A unique violation is classified by its PostgreSQL constraint IDENTIFIER token, never by a localized
    // message prefix: the server may answer in Spanish with mangled quoting, yet `pacientes_ci_nit_key` survives.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
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
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state) && violates(failure, DUPLICATE_CI_NIT)) return "PATIENT_DUPLICATE";
                // 22021/22P05: a value (for example NUL inside a text column) that PostgreSQL rejects outright.
                if ("22001".equals(state) || "22021".equals(state) || "22P05".equals(state)
                        || "23502".equals(state) || "23514".equals(state)) return "INVALID_INPUT";
            }
            if (cause instanceof BusinessRuleException || cause instanceof IllegalArgumentException) return "INVALID_INPUT";
        }
        return "TECHNICAL_RETRY_REQUIRED";
    }

    private static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            var identifiers = IDENTIFIER.matcher(message);
            while (identifiers.find()) {
                String token = identifiers.group();
                if (token.equals(constraint) || token.startsWith(constraint + "_")) return true;
            }
        }
        return false;
    }

}
