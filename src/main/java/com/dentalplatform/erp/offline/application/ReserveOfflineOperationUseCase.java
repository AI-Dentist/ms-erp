package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReserveOfflineOperationUseCase {
    private final ConfirmOfflineSyncUseCase sessions;
    private final OfflineOperationStore operations;
    private final OfflinePayloadCodec codec;

    public ReserveOfflineOperationUseCase(ConfirmOfflineSyncUseCase sessions, OfflineOperationStore operations,
                                          OfflinePayloadCodec codec) {
        this.sessions = sessions;
        this.operations = operations;
        this.codec = codec;
    }

    /** Persists immutable intent metadata only. PREPARED never means the business action ran. */
    @Transactional
    public OfflineOperationReceipt reserve(OfflineOperationRequest request) {
        if (request == null || request.sessionId() == null || request.operationId() == null || request.type() == null) {
            throw new OfflineSyncFailure("INVALID_INPUT");
        }
        var context = sessions.requireConfirmed(request.sessionId());
        var caller = context.caller();
        if (!OfflineOperationStatusUseCase.allowed(caller.role(), request.type())) {
            throw new OfflineSyncFailure("PERMISSION_REVOKED");
        }
        String hash = codec.hash(request.type(), request.payload());
        operations.reserve(caller.actorId(), request.operationId(), caller.issuer(), caller.subject(), request.type(), hash);
        var stored = operations.lockHeader(caller.actorId(), request.operationId())
                .orElseThrow(() -> new IllegalStateException("Reserved offline intent disappeared"));
        sessions.requireConfirmed(request.sessionId()); // Recheck time after a contended unique-key/row wait.
        if (!stored.issuer().equals(caller.issuer()) || !stored.subject().equals(caller.subject())) {
            return conflict(request, null, "ACTOR_LINK_CHANGED");
        }
        if (!OfflineOperationStatusUseCase.allowed(caller.role(), stored.type())) {
            throw new OfflineSyncFailure("PERMISSION_REVOKED");
        }
        if (stored.type() != request.type()) return conflict(request, stored.payloadHash(), "OPERATION_TYPE_MISMATCH");
        if (!stored.payloadHash().equals(hash)) return conflict(request, stored.payloadHash(), "PAYLOAD_MISMATCH");
        return operations.receipt(caller.actorId(), request.operationId())
                .orElseThrow(() -> new IllegalStateException("Reserved offline intent disappeared"));
    }

    private static OfflineOperationReceipt conflict(OfflineOperationRequest request, String storedHash, String reason) {
        return new OfflineOperationReceipt(request.operationId(), request.type(), OfflineOperationReceipt.State.CONFLICT,
                storedHash, reason, null, null);
    }
}
