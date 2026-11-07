package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Database-level guards for the offline intent ledger. */
class OfflineOperationSchemaTest extends OfflineOperationHttpTestSupport {
    /** Every column the V14 trigger freezes at reservation time. */
    private static final String[] FROZEN = {"actor_id", "operation_id", "identity_issuer", "identity_subject",
            "operation_type", "canonical_version", "payload_hash"};
    @Autowired JdbcTemplate jdbc;
    @Autowired OfflineOperationStore operations;

    private void insert(UUID actor, UUID operation) {
        jdbc.update("""
                INSERT INTO offline_operation_receipts
                    (actor_id, operation_id, identity_issuer, identity_subject, operation_type, payload_hash, state)
                VALUES (?, ?, 'https://synthetic-issuer', 'synthetic-subject', 'PATIENT_CREATE', ?, 'PREPARED')
                """, actor, operation, "a".repeat(64));
    }

    private void insertSession(UUID session, UUID actor, String issuer, String subject) {
        jdbc.update("""
                INSERT INTO offline_sync_sessions
                    (session_id, actor_id, issuer, subject, workspace_id, device_id,
                     reauth_not_before, challenge_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), now() + interval '1 hour')
                """, session, actor, issuer, subject, UUID.randomUUID(), UUID.randomUUID());
    }

    /** The stored state and reason of one ledger row, or a failure if the row disappeared. */
    private String denialRow(UUID actor, UUID operation) {
        return jdbc.queryForObject("""
                SELECT state || '|' || coalesce(reason_code, '-')
                FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?
                """, String.class, actor, operation);
    }

    private Object rewritten(String column) {
        return switch (column) {
            case "actor_id", "operation_id" -> UUID.randomUUID();
            // canonical_version can only ever be 1 in the ledger, so the trigger runs before the CHECK.
            case "canonical_version" -> 2;
            case "operation_type" -> "CASH_EXPENSE";
            case "payload_hash" -> "b".repeat(64);
            default -> "rewritten";
        };
    }

    /**
     * FIX 4(a) guard: the denial write is scoped by the session identity (s.issuer, s.subject) <em>and</em> by the
     * frozen reservation identity (r.identity_issuer, r.identity_subject). Deleting either predicate would let a
     * caller that only knows an actor's sessionId and operationId move another identity's intent to BLOCKED.
     */
    @Test
    void denialWritesNothingUnlessTheCallerOwnsBothTheSessionAndTheReservation() throws Exception {
        UUID owner = UUID.fromString(OfflineSyncHttpSupport.createUser(port, "ADMIN").id());
        UUID operation = UUID.randomUUID();
        UUID ownerSession = UUID.randomUUID();
        UUID foreignSession = UUID.randomUUID();
        String issuer = "https://synthetic-issuer";
        String ownerSubject = "synthetic-subject";
        String callerSubject = "synthetic-intruder";
        insert(owner, operation);
        insertSession(ownerSession, owner, issuer, ownerSubject);
        insertSession(foreignSession, owner, issuer, callerSubject);
        try {
            // Same session row, but the caller's validated subject is not the session's subject.
            operations.recordSessionDenial(ownerSession, operation, "SESSION_EXPIRED", issuer, callerSubject);
            assertThat(denialRow(owner, operation)).isEqualTo("PREPARED|-");
            // Same subject from another issuer: an identity of a different realm is not the owner either.
            operations.recordSessionDenial(ownerSession, operation, "SESSION_EXPIRED", "https://other-issuer", ownerSubject);
            assertThat(denialRow(owner, operation)).isEqualTo("PREPARED|-");
            // The session matches this caller, but the reservation was frozen under another identity, so the
            // reservation predicates alone must still refuse the write.
            operations.recordSessionDenial(foreignSession, operation, "SESSION_EXPIRED", issuer, callerSubject);
            assertThat(denialRow(owner, operation)).isEqualTo("PREPARED|-");
            // Control: the matching principal still records its own denial.
            operations.recordSessionDenial(ownerSession, operation, "SESSION_EXPIRED", issuer, ownerSubject);
            assertThat(denialRow(owner, operation)).isEqualTo("BLOCKED|SESSION_EXPIRED");
        } finally {
            jdbc.update("DELETE FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?", owner, operation);
            jdbc.update("DELETE FROM offline_sync_sessions WHERE session_id = ? OR session_id = ?",
                    ownerSession, foreignSession);
        }
    }

    @Test
    void intentColumnsCannotBeRewrittenAndConfirmationNeedsARealResult() throws Exception {
        UUID actor = UUID.fromString(OfflineSyncHttpSupport.createUser(port, "ADMIN").id());
        UUID operation = UUID.randomUUID();
        insert(actor, operation);
        try {
            for (String column : FROZEN) {
                assertThatThrownBy(() -> jdbc.update("UPDATE offline_operation_receipts SET " + column + " = ? "
                        + "WHERE actor_id = ? AND operation_id = ?", rewritten(column), actor, operation))
                        .as(column).isInstanceOf(DataAccessException.class);
            }
            // Rejected statements never partially applied, so the frozen intent is still the original one.
            assertThat(jdbc.queryForObject("SELECT payload_hash FROM offline_operation_receipts "
                    + "WHERE actor_id = ? AND operation_id = ?", String.class, actor, operation))
                    .isEqualTo("a".repeat(64));
            assertThat(jdbc.queryForObject("SELECT operation_type FROM offline_operation_receipts "
                    + "WHERE actor_id = ? AND operation_id = ?", String.class, actor, operation))
                    .isEqualTo("PATIENT_CREATE");

            // The smallest version the ledger accepts is 1, so canonical_version is also proved structurally:
            // the installed trigger function guards all seven columns, not only the ones a valid row can change.
            String function = jdbc.queryForObject("SELECT prosrc FROM pg_proc "
                    + "WHERE proname = 'offline_operation_receipts_reject_intent_change'", String.class);
            for (String column : FROZEN) {
                assertThat(function).as(column).contains("NEW." + column + " IS DISTINCT FROM OLD." + column);
            }
            assertThat(jdbc.queryForObject("SELECT pg_get_triggerdef(oid) FROM pg_trigger "
                    + "WHERE tgrelid = 'offline_operation_receipts'::regclass "
                    + "AND tgname = 'offline_operation_receipts_immutable_intent'", String.class))
                    .contains("BEFORE UPDATE", "FOR EACH ROW",
                            "offline_operation_receipts_reject_intent_change");

            // A non-immutable transition is still allowed, so the guard is not over-broad.
            assertThat(jdbc.update("UPDATE offline_operation_receipts SET state = 'BLOCKED', reason_code = 'SESSION_EXPIRED' "
                    + "WHERE actor_id = ? AND operation_id = ?", actor, operation)).isEqualTo(1);
            // The persistence layer translates the guard, so assert the observable message and that nothing confirmed.
            assertThatThrownBy(() -> operations.confirm(actor, operation, null, OffsetDateTime.now()))
                    .hasMessageContaining("requires a result");
            assertThat(jdbc.queryForObject("SELECT state FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?",
                    String.class, actor, operation)).isEqualTo("BLOCKED");
        } finally {
            jdbc.update("DELETE FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?", actor, operation);
        }
    }
}
