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
    @Autowired JdbcTemplate jdbc;
    @Autowired OfflineOperationStore operations;

    private void insert(UUID actor, UUID operation) {
        jdbc.update("""
                INSERT INTO offline_operation_receipts
                    (actor_id, operation_id, identity_issuer, identity_subject, operation_type, payload_hash, state)
                VALUES (?, ?, 'https://synthetic-issuer', 'synthetic-subject', 'PATIENT_CREATE', ?, 'PREPARED')
                """, actor, operation, "a".repeat(64));
    }

    @Test
    void intentColumnsCannotBeRewrittenAndConfirmationNeedsARealResult() throws Exception {
        UUID actor = UUID.fromString(OfflineSyncHttpSupport.createUser(port, "ADMIN").id());
        UUID operation = UUID.randomUUID();
        insert(actor, operation);
        try {
            for (String column : new String[]{"identity_issuer", "identity_subject", "operation_type", "payload_hash"}) {
                String value = column.equals("operation_type") ? "CASH_EXPENSE"
                        : column.equals("payload_hash") ? "b".repeat(64) : "rewritten";
                assertThatThrownBy(() -> jdbc.update("UPDATE offline_operation_receipts SET " + column + " = ? "
                        + "WHERE actor_id = ? AND operation_id = ?", value, actor, operation))
                        .as(column).isInstanceOf(DataAccessException.class);
            }
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
