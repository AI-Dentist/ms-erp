package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import java.util.Map;
import java.util.UUID;

/** Executes through existing transactional business use cases, never through arbitrary client SQL. */
public interface OfflineOperationHandler {
    OfflineOperationReceipt.Type type();
    OfflineOperationReceipt.Result execute(Map<String, Object> payload, UUID actorId);
}
