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
    Optional<OfflineOperationReceipt> receipt(UUID actorId, UUID operationId);
}
