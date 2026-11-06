package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt.Type;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public record OfflineOperationRequest(UUID sessionId, UUID operationId, Type type,
                                      Map<String, Object> cash, Map<String, Object> expense,
                                      Map<String, Object> order, Map<String, Object> patient) {
    public Map<String, Object> payload() {
        if (type == null || Stream.of(cash, expense, order, patient).filter(Objects::nonNull).count() != 1) {
            throw new OfflineSyncFailure("INVALID_INPUT");
        }
        Map<String, Object> selected = switch (type) {
            case CASH_PAYMENT -> cash;
            case CASH_EXPENSE -> expense;
            case MANUAL_ORDER -> order;
            case PATIENT_CREATE -> patient;
        };
        if (selected == null) throw new OfflineSyncFailure("INVALID_INPUT");
        return selected;
    }
}
