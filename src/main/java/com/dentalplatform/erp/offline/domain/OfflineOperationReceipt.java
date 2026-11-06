package com.dentalplatform.erp.offline.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Durable operation outcome; NOT_FOUND is a response, never a stored confirmation. */
public record OfflineOperationReceipt(UUID operationId, Type type, State state, String payloadHash,
                                      String reasonCode, OffsetDateTime confirmedAt, Result result) {
    public enum Type { CASH_PAYMENT, CASH_EXPENSE, MANUAL_ORDER, PATIENT_CREATE }
    public enum State { PREPARED, CONFIRMED, CONFLICT, BLOCKED, NOT_FOUND }
    public record Installment(int number, UUID id) { }
    public record Result(UUID patientId, UUID orderId, List<Installment> installments,
                         UUID movementId, UUID receiptId) {
        public Result {
            installments = installments == null ? List.of() : List.copyOf(installments);
        }
    }
    public static OfflineOperationReceipt notFound(UUID operationId) {
        return new OfflineOperationReceipt(operationId, null, State.NOT_FOUND, null, null, null, null);
    }
}
