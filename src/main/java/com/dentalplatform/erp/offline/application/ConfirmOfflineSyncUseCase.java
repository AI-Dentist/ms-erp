package com.dentalplatform.erp.offline.application;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ConfirmOfflineSyncUseCase {
    private final OfflineSyncIdentityPort identity;
    private final OfflineSyncSessionStore sessions;
    private final Clock clock;

    public ConfirmOfflineSyncUseCase(OfflineSyncIdentityPort identity, OfflineSyncSessionStore sessions, Clock clock) {
        this.identity = identity;
        this.sessions = sessions;
        this.clock = clock;
    }

    public record ConfirmedSession(UUID sessionId, UUID actorId, UUID workspaceId, UUID deviceId,
                                   OffsetDateTime authenticatedAt, OffsetDateTime expiresAt) { }
    public record Context(OfflineSyncIdentityPort.Caller caller, OfflineSyncSessionStore.Session session) { }

    @Transactional
    public ConfirmedSession confirm(UUID sessionId) {
        var caller = identity.current();
        var session = sessions.lock(sessionId).orElseThrow(() -> failure("SESSION_UNAVAILABLE"));
        Instant now = clock.instant();
        checkContext(caller, session, now);
        if (session.authenticatedAt() == null) {
            if (!session.challengeExpiresAt().isAfter(now)) throw failure("SESSION_EXPIRED");
        } else if (session.expiresAt() == null || !session.expiresAt().isAfter(now)) {
            throw failure("SESSION_EXPIRED");
        }
        Instant auth = authenticationTime(caller.authTime());
        if (auth.isBefore(session.reauthNotBefore()) || auth.isAfter(now)
                || auth.isBefore(now.minusSeconds(300))) throw failure("FRESH_LOGIN_REQUIRED");
        if (session.authenticatedAt() != null) {
            if (!session.authenticatedAt().equals(auth)) throw failure("SESSION_ALREADY_BOUND");
        } else {
            Instant expiry = caller.tokenExpiresAt().truncatedTo(ChronoUnit.MICROS);
            if (!sessions.activate(sessionId, auth, expiry)) throw failure("SESSION_UNAVAILABLE");
            session = sessions.lock(sessionId).orElseThrow(() -> failure("SESSION_UNAVAILABLE"));
        }
        return new ConfirmedSession(session.sessionId(), session.actorId(), session.workspaceId(), session.deviceId(),
                session.authenticatedAt().atOffset(ZoneOffset.UTC), session.expiresAt().atOffset(ZoneOffset.UTC));
    }

    /** Joins the business transaction so the identity and session locks remain held through its work. */
    @Transactional
    public Context requireConfirmed(UUID sessionId) {
        var caller = identity.current();
        var session = sessions.lock(sessionId).orElseThrow(() -> failure("SESSION_UNAVAILABLE"));
        Instant now = clock.instant();
        checkContext(caller, session, now);
        if (session.authenticatedAt() == null) throw failure("SESSION_UNAVAILABLE");
        if (session.expiresAt() == null || !session.expiresAt().isAfter(now)) throw failure("SESSION_EXPIRED");
        Instant auth = authenticationTime(caller.authTime());
        if (!session.authenticatedAt().equals(auth)) throw failure("SESSION_ALREADY_BOUND");
        return new Context(caller, session);
    }

    private static void checkContext(OfflineSyncIdentityPort.Caller caller, OfflineSyncSessionStore.Session session,
                                     Instant now) {
        if (!session.actorId().equals(caller.actorId()) || !session.issuer().equals(caller.issuer())
                || !session.subject().equals(caller.subject())) throw failure("SESSION_ACTOR_MISMATCH");
        if (caller.tokenExpiresAt() == null || !caller.tokenExpiresAt().isAfter(now)) {
            throw failure("ACTOR_UNAVAILABLE");
        }
    }

    private static Instant authenticationTime(Object value) {
        if (!(value instanceof Number number)) throw failure("FRESH_LOGIN_REQUIRED");
        try {
            return Instant.ofEpochSecond(new BigDecimal(number.toString()).longValueExact());
        } catch (NumberFormatException | ArithmeticException | DateTimeException ex) {
            throw failure("FRESH_LOGIN_REQUIRED");
        }
    }

    private static OfflineSyncFailure failure(String code) {
        return new OfflineSyncFailure(code);
    }
}
