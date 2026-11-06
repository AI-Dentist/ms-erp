package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OfflineOperationStatusUseCase {
    private final ConfirmOfflineSyncUseCase sessions;
    private final OfflineOperationStore operations;

    public OfflineOperationStatusUseCase(ConfirmOfflineSyncUseCase sessions, OfflineOperationStore operations) {
        this.sessions = sessions;
        this.operations = operations;
    }

    // PostgreSQL permission/session row locks require a read-write transaction even for this query.
    @Transactional
    public OfflineOperationReceipt status(UUID sessionId, UUID operationId) {
        var context = sessions.requireConfirmed(sessionId);
        if (operationId == null) throw new OfflineSyncFailure("INVALID_INPUT");
        var caller = context.caller();
        var header = operations.lockHeader(caller.actorId(), operationId);
        if (header.isEmpty()) return OfflineOperationReceipt.notFound(operationId);
        var intent = header.get();
        if (!intent.issuer().equals(caller.issuer()) || !intent.subject().equals(caller.subject())) {
            return new OfflineOperationReceipt(operationId, null, OfflineOperationReceipt.State.CONFLICT,
                    null, "ACTOR_LINK_CHANGED", null, null);
        }
        if (!allowed(caller.role(), intent.type())) throw new OfflineSyncFailure("PERMISSION_REVOKED");
        return operations.receipt(caller.actorId(), operationId)
                .orElseThrow(() -> new IllegalStateException("Locked offline intent disappeared"));
    }

    static boolean allowed(String role, OfflineOperationReceipt.Type type) {
        return switch (role) {
            case "ADMIN" -> true;
            case "CAJERO" -> type != OfflineOperationReceipt.Type.PATIENT_CREATE;
            case "RECEPCIONISTA" -> type == OfflineOperationReceipt.Type.PATIENT_CREATE
                    || type == OfflineOperationReceipt.Type.MANUAL_ORDER;
            default -> false;
        };
    }
}
