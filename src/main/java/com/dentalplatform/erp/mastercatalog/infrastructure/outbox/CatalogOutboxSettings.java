package com.dentalplatform.erp.mastercatalog.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hard limits keep a mistaken deployment setting from making broker outages hold DB locks indefinitely. */
@Component
public record CatalogOutboxSettings(int batchSize, long sendTimeoutMs, long initialBackoffMs, long maxBackoffMs) {
    public CatalogOutboxSettings(
            @Value("${erp.catalog-outbox.batch-size:1}") int batchSize,
            @Value("${erp.catalog-outbox.send-timeout-ms:2000}") long sendTimeoutMs,
            @Value("${erp.catalog-outbox.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${erp.catalog-outbox.max-backoff-ms:300000}") long maxBackoffMs) {
        if (batchSize < 1 || batchSize > 10 || sendTimeoutMs < 50 || sendTimeoutMs > 3000
                || initialBackoffMs < 100 || maxBackoffMs < initialBackoffMs || maxBackoffMs > 86400000) {
            throw new IllegalArgumentException("Invalid catalog outbox limits; see docs/catalog-outbox.md");
        }
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    public long backoffMs(int attempts) {
        long delay = initialBackoffMs;
        for (int i = 1; i < attempts && delay < maxBackoffMs; i++) {
            delay = Math.min(maxBackoffMs, delay * 2);
        }
        return delay;
    }
}
