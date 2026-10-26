package com.dentalplatform.erp.shared.infrastructure.idempotency;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyRecordJpaRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public JpaIdempotencyStore(IdempotencyRecordJpaRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ClaimResult claim(String key, String operation, Instant expiresAt) {
        repository.deleteIfExpired(key, now());
        if (repository.tryInsert(key, operation, expiresAt.atOffset(ZoneOffset.UTC)) == 1) {
            return ClaimResult.acquired();
        }
        return repository.findById(key)
                .map(existing -> {
                    if (!existing.getOperationName().equals(operation)) {
                        return ClaimResult.operationMismatch();
                    }
                    if (existing.getResponsePayload() == null) {
                        return ClaimResult.inProgress();
                    }
                    return ClaimResult.completed(jsonMapper.readValue(existing.getResponsePayload(), StoredResponse.class));
                })
                // released by a concurrent request between the insert and the read: let the client retry
                .orElseGet(ClaimResult::inProgress);
    }

    @Override
    @Transactional
    public void complete(String key, StoredResponse response) {
        repository.storeResponse(key, jsonMapper.writeValueAsString(response));
    }

    @Override
    @Transactional
    public void release(String key) {
        repository.deleteById(key);
    }

    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    public void purgeExpired() {
        repository.purgeExpired(now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
