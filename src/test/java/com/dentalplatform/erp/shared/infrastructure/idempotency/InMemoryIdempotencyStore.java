package com.dentalplatform.erp.shared.infrastructure.idempotency;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(String operation, StoredResponse response) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public ClaimResult claim(String key, String operation, Instant expiresAt) {
        Entry existing = entries.putIfAbsent(key, new Entry(operation, null));
        if (existing == null) {
            return ClaimResult.acquired();
        }
        if (!existing.operation().equals(operation)) {
            return ClaimResult.operationMismatch();
        }
        return existing.response() == null ? ClaimResult.inProgress() : ClaimResult.completed(existing.response());
    }

    @Override
    public void complete(String key, StoredResponse response) {
        entries.computeIfPresent(key, (k, e) -> new Entry(e.operation(), response));
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }

    int size() {
        return entries.size();
    }
}
