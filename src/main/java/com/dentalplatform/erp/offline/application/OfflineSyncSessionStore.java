package com.dentalplatform.erp.offline.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Session metadata access within the caller's transaction. */
public interface OfflineSyncSessionStore {
    record Session(UUID sessionId, UUID actorId, String issuer, String subject,
                   UUID workspaceId, UUID deviceId, Instant reauthNotBefore,
                   Instant challengeExpiresAt, Instant authenticatedAt, Instant expiresAt) { }

    void create(Session session);
    Optional<Session> lock(UUID sessionId);
    boolean activate(UUID sessionId, Instant authenticatedAt, Instant expiresAt);
    BigDecimal cashflowBalance();
}
