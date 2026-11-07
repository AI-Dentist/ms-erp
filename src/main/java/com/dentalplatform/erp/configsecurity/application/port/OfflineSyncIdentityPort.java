package com.dentalplatform.erp.configsecurity.application.port;

import java.time.Instant;
import java.util.UUID;

/** Live identity for a sync operation; callers must retain the surrounding transaction. */
public interface OfflineSyncIdentityPort {
    Caller current();

    /**
     * Validated principal of the caller as proven by the request token, independent of the current database
     * role. Used only to authorize an audit-style denial write when the live identity is already unusable.
     */
    Principal principal();

    record Principal(String issuer, String subject) { }

    record Caller(UUID actorId, String issuer, String subject, String role,
                  Object authTime, Instant tokenExpiresAt) { }
}
