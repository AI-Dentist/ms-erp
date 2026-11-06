package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class ExecuteOfflineOperationUseCase {
    private final ConfirmOfflineSyncUseCase sessions;
    private final OfflineOperationStore operations;
    private final OfflinePayloadCodec codec;
    private final Clock clock;
    private final Map<OfflineOperationReceipt.Type, OfflineOperationHandler> handlers;

    public ExecuteOfflineOperationUseCase(ConfirmOfflineSyncUseCase sessions, OfflineOperationStore operations,
                                          OfflinePayloadCodec codec, Clock clock, List<OfflineOperationHandler> handlers) {
        this.sessions = sessions;
        this.operations = operations;
        this.codec = codec;
        this.clock = clock;
        this.handlers = new EnumMap<>(OfflineOperationReceipt.Type.class);
        for (var handler : handlers) {
            if (this.handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Duplicate offline operation handler");
            }
        }
    }

    public boolean supports(OfflineOperationReceipt.Type type) {
        return handlers.containsKey(type);
    }

    private record Locked(ConfirmOfflineSyncUseCase.Context context, OfflineOperationReceipt receipt) { }

    private Locked lock(OfflineOperationRequest request) {
        var context = sessions.requireConfirmed(request.sessionId());
        var caller = context.caller();
        if (!OfflineOperationStatusUseCase.allowed(caller.role(), request.type())) {
            throw new OfflineSyncFailure("PERMISSION_REVOKED");
        }
        var header = operations.lockForExecution(caller.actorId(), request.operationId())
                .orElseThrow(() -> new OfflineSyncFailure("INVALID_INPUT"));
        sessions.requireConfirmed(request.sessionId());
        if (!header.issuer().equals(caller.issuer()) || !header.subject().equals(caller.subject())) {
            throw new OfflineSyncFailure("ACTOR_LINK_CHANGED");
        }
        if (!OfflineOperationStatusUseCase.allowed(caller.role(), header.type())) {
            throw new OfflineSyncFailure("PERMISSION_REVOKED");
        }
        if (header.type() != request.type() || !header.payloadHash().equals(codec.hash(request.type(), request.payload()))) {
            throw new OfflineSyncFailure("PAYLOAD_MISMATCH");
        }
        return new Locked(context, operations.receipt(caller.actorId(), request.operationId()).orElseThrow());
    }

    @Transactional
    public OfflineOperationReceipt execute(OfflineOperationRequest request) {
        var locked = lock(request);
        var current = locked.receipt();
        if (current.state() != OfflineOperationReceipt.State.PREPARED
                && current.state() != OfflineOperationReceipt.State.BLOCKED) return current;
        var handler = handlers.get(request.type());
        if (handler == null) return current;
        var result = handler.execute(request.payload(), locked.context().caller().actorId());
        sessions.requireConfirmed(request.sessionId());
        operations.confirm(locked.context().caller().actorId(), request.operationId(), result,
                clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
        return operations.receipt(locked.context().caller().actorId(), request.operationId()).orElseThrow();
    }

    /** Called through the proxy only AFTER execute has returned exceptionally and rolled back. */
    @Transactional
    public OfflineOperationReceipt recover(OfflineOperationRequest request, String reason,
                                            OfflineOperationReceipt.State state) {
        var locked = lock(request);
        var current = locked.receipt();
        if (current.state() == OfflineOperationReceipt.State.CONFIRMED
                || current.state() == OfflineOperationReceipt.State.CONFLICT) return current;
        operations.failure(locked.context().caller().actorId(), request.operationId(), reason, state);
        return operations.receipt(locked.context().caller().actorId(), request.operationId()).orElseThrow();
    }
}
