package com.dentalplatform.erp.offline.infrastructure.persistence;

import com.dentalplatform.erp.offline.application.OfflineSyncSessionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOfflineSyncSessionStore implements OfflineSyncSessionStore {
    private final JdbcTemplate jdbc;

    public JdbcOfflineSyncSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(Session session) {
        jdbc.update("""
                INSERT INTO offline_sync_sessions
                (session_id, actor_id, issuer, subject, workspace_id, device_id, reauth_not_before,
                 challenge_expires_at, authenticated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session.sessionId(), session.actorId(), session.issuer(), session.subject(),
                session.workspaceId(), session.deviceId(), timestamp(session.reauthNotBefore()),
                timestamp(session.challengeExpiresAt()), timestamp(session.authenticatedAt()), timestamp(session.expiresAt()));
    }

    @Override
    public Optional<Session> lock(UUID sessionId) {
        return jdbc.query("SELECT * FROM offline_sync_sessions WHERE session_id = ? FOR UPDATE",
                (rs, row) -> new Session(rs.getObject("session_id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getString("issuer"), rs.getString("subject"), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("device_id", UUID.class), instant(rs, "reauth_not_before"),
                        instant(rs, "challenge_expires_at"), instant(rs, "authenticated_at"), instant(rs, "expires_at")),
                sessionId).stream().findFirst();
    }

    @Override
    public boolean activate(UUID sessionId, Instant authenticatedAt, Instant expiresAt) {
        return jdbc.update("""
                UPDATE offline_sync_sessions SET authenticated_at = ?, expires_at = ?
                WHERE session_id = ? AND authenticated_at IS NULL AND expires_at IS NULL
                """, timestamp(authenticatedAt), timestamp(expiresAt), sessionId) == 1;
    }

    @Override
    public BigDecimal cashflowBalance() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE tipo_movimiento WHEN 'INGRESO' THEN monto_bs
                    WHEN 'EGRESO' THEN -monto_bs ELSE 0 END), 0) FROM movimientos_caja
                """, BigDecimal.class);
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
