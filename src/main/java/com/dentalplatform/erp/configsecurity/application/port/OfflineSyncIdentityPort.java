package com.dentalplatform.erp.configsecurity.application.port;

import java.time.Instant;
import java.util.UUID;

/** Live identity for a sync operation; callers must retain the surrounding transaction. */
public interface OfflineSyncIdentityPort {
    Caller current();

    record Caller(UUID actorId, String issuer, String subject, String role,
                  Object authTime, Instant tokenExpiresAt) { }
}
