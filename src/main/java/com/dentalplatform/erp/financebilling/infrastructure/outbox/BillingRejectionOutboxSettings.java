package com.dentalplatform.erp.financebilling.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment limits also bound the duration of each event's database lock. */
@Component
public record BillingRejectionOutboxSettings(int maxAttempts, int batchSize, long sendTimeoutMs,
                                            long initialBackoffMs, long maxBackoffMs, long pollDelayMs) {
    public BillingRejectionOutboxSettings(
            @Value("${erp.billing-saga.outbox.max-attempts:5}") int maxAttempts,
            @Value("${erp.billing-saga.outbox.batch-size:1}") int batchSize,
            @Value("${erp.billing-saga.outbox.send-timeout-ms:2000}") long sendTimeoutMs,
            @Value("${erp.billing-saga.outbox.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${erp.billing-saga.outbox.max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${erp.billing-saga.outbox.poll-delay-ms:1000}") long pollDelayMs) {
        if (maxAttempts < 1 || maxAttempts > 10 || batchSize < 1 || batchSize > 10
                || sendTimeoutMs < 50 || sendTimeoutMs > 3000 || initialBackoffMs < 10
                || maxBackoffMs < initialBackoffMs || maxBackoffMs > 86400000
                || pollDelayMs < 50 || pollDelayMs > 60000) {
            throw new IllegalArgumentException("Invalid billing rejection outbox limits");
        }
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.pollDelayMs = pollDelayMs;
    }

    public long backoffMs(int attempts) {
        long delay = initialBackoffMs;
        for (int i = 1; i < attempts && delay < maxBackoffMs; i++) {
            delay = delay > maxBackoffMs / 2 ? maxBackoffMs : delay * 2;
        }
        return delay;
    }
}
