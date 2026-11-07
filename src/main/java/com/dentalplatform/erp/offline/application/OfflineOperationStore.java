package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import java.util.Optional;
import java.util.UUID;

/** Reads authorization metadata separately from a potentially confirmed result. */
public interface OfflineOperationStore {
    record Header(String issuer, String subject, OfflineOperationReceipt.Type type, String payloadHash) { }
    void reserve(UUID actorId, UUID operationId, String issuer, String subject,
                 OfflineOperationReceipt.Type type, String payloadHash);
    Optional<Header> lockHeader(UUID actorId, UUID operationId);
    Optional<Header> lockForExecution(UUID actorId, UUID operationId);
    void confirm(UUID actorId, UUID operationId, OfflineOperationReceipt.Result result, java.time.OffsetDateTime at);
    void failure(UUID actorId, UUID operationId, String reason, OfflineOperationReceipt.State state);
    /**
     * Records a denial on the reservation bound to this session. The session row supplies the actor, so a revoked
     * or mismatched live identity cannot prevent the denial from being written; CONFIRMED rows are never touched.
     */
    void recordSessionDenial(UUID sessionId, UUID operationId, String reason);
    Optional<OfflineOperationReceipt> receipt(UUID actorId, UUID operationId);
}
