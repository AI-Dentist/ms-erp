package com.dentalplatform.erp.shared.infrastructure.idempotency;

import java.time.Instant;

/**
 * Persistence of idempotency keys. {@link #claim} must be atomic so concurrent requests with the same key
 * cannot both acquire it.
 */
public interface IdempotencyStore {

    ClaimResult claim(String key, String operation, Instant expiresAt);

    void complete(String key, StoredResponse response);

    void release(String key);

    record StoredResponse(int status, String contentType, String body) {
    }

    enum ClaimStatus { ACQUIRED, IN_PROGRESS, COMPLETED, OPERATION_MISMATCH }

    record ClaimResult(ClaimStatus status, StoredResponse response) {

        public static ClaimResult acquired() {
            return new ClaimResult(ClaimStatus.ACQUIRED, null);
        }

        public static ClaimResult inProgress() {
            return new ClaimResult(ClaimStatus.IN_PROGRESS, null);
        }

        public static ClaimResult completed(StoredResponse response) {
            return new ClaimResult(ClaimStatus.COMPLETED, response);
        }

        public static ClaimResult operationMismatch() {
            return new ClaimResult(ClaimStatus.OPERATION_MISMATCH, null);
        }
    }
}
