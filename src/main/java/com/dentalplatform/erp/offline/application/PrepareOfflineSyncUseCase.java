package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class PrepareOfflineSyncUseCase {
    private final OfflineSyncIdentityPort identity;
    private final OfflineSyncSessionStore sessions;
    private final Clock clock;

    public PrepareOfflineSyncUseCase(OfflineSyncIdentityPort identity, OfflineSyncSessionStore sessions, Clock clock) {
        this.identity = identity;
        this.sessions = sessions;
        this.clock = clock;
    }

    public record Challenge(UUID sessionId, UUID actorId, UUID workspaceId, UUID deviceId,
                            OffsetDateTime reauthNotBefore, OffsetDateTime expiresAt, BigDecimal cashflowBalanceBs) { }

    @Transactional
    public Challenge prepare(UUID workspaceId, UUID deviceId, UUID expectedActorId) {
        if (workspaceId == null || deviceId == null || expectedActorId == null) {
            throw new OfflineSyncFailure("INVALID_INPUT");
        }
        var caller = identity.current();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (caller.tokenExpiresAt() == null || !caller.tokenExpiresAt().isAfter(now)) {
            throw new OfflineSyncFailure("ACTOR_UNAVAILABLE");
        }
        if (!caller.actorId().equals(expectedActorId)) {
            throw new OfflineSyncFailure("SESSION_ACTOR_MISMATCH");
        }
        Instant boundary = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        Instant expires = now.plus(24, ChronoUnit.HOURS);
        UUID id = UUID.randomUUID();
        sessions.create(new OfflineSyncSessionStore.Session(id, caller.actorId(), caller.issuer(), caller.subject(),
                workspaceId, deviceId, boundary, expires, null, null));
        BigDecimal balance = caller.role().equals("RECEPCIONISTA") ? null : sessions.cashflowBalance();
        return new Challenge(id, caller.actorId(), workspaceId, deviceId, boundary.atOffset(ZoneOffset.UTC),
                expires.atOffset(ZoneOffset.UTC), balance);
    }
}
