package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt.Type;
import java.util.Map;

/** Version-one canonical frozen typed payload; excludes session, actor and transport metadata. */
public interface OfflinePayloadCodec {
    int VERSION = 1;
    String canonical(Type type, Map<String, Object> payload);
    String hash(Type type, Map<String, Object> payload);
}
