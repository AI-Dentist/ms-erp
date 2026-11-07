package com.dentalplatform.erp.offline.infrastructure.persistence;

import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOfflineOperationStore implements OfflineOperationStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public JdbcOfflineOperationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void reserve(UUID actorId, UUID operationId, String issuer, String subject,
                        OfflineOperationReceipt.Type type, String payloadHash) {
        jdbc.update("""
                INSERT INTO offline_operation_receipts
                    (actor_id, operation_id, identity_issuer, identity_subject, operation_type, payload_hash, state)
                VALUES (?, ?, ?, ?, ?, ?, 'PREPARED')
                ON CONFLICT (actor_id, operation_id) DO NOTHING
                """, actorId, operationId, issuer, subject, type.name(), payloadHash);
    }

    @Override
    public Optional<Header> lockHeader(UUID actorId, UUID operationId) {
        return jdbc.query("""
                SELECT identity_issuer, identity_subject, operation_type, payload_hash
                FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?
                FOR SHARE
                """, (rs, row) -> new Header(rs.getString("identity_issuer"), rs.getString("identity_subject"),
                OfflineOperationReceipt.Type.valueOf(rs.getString("operation_type")), rs.getString("payload_hash")), actorId, operationId)
                .stream().findFirst();
    }

    @Override
    public Optional<Header> lockForExecution(UUID actorId, UUID operationId) {
        return jdbc.query("""
                SELECT identity_issuer, identity_subject, operation_type, payload_hash
                FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ? FOR UPDATE
                """, (rs, row) -> new Header(rs.getString("identity_issuer"), rs.getString("identity_subject"),
                OfflineOperationReceipt.Type.valueOf(rs.getString("operation_type")), rs.getString("payload_hash")),
                actorId, operationId).stream().findFirst();
    }

    @Override
    public void confirm(UUID actorId, UUID operationId, OfflineOperationReceipt.Result result, OffsetDateTime at) {
        // CONFIRMED must always carry a real business result; JSON null would satisfy the NOT NULL check.
        if (result == null) throw new IllegalArgumentException("Offline confirmation requires a result");
        int changed = jdbc.update("""
                UPDATE offline_operation_receipts
                SET state = 'CONFIRMED', result_json = CAST(? AS JSONB), confirmed_at = ?, reason_code = NULL
                WHERE actor_id = ? AND operation_id = ? AND state IN ('PREPARED', 'BLOCKED')
                """, JSON.writeValueAsString(result), at, actorId, operationId);
        if (changed != 1) throw new IllegalStateException("Offline confirmation lost its locked intent");
    }

    @Override
    public void failure(UUID actorId, UUID operationId, String reason, OfflineOperationReceipt.State state) {
        if (state != OfflineOperationReceipt.State.PREPARED && state != OfflineOperationReceipt.State.CONFLICT
                && state != OfflineOperationReceipt.State.BLOCKED) throw new IllegalArgumentException("Invalid failure state");
        jdbc.update("""
                UPDATE offline_operation_receipts SET state = ?, reason_code = ?, result_json = NULL, confirmed_at = NULL
                WHERE actor_id = ? AND operation_id = ? AND state <> 'CONFIRMED'
                """, state.name(), reason, actorId, operationId);
    }

    @Override
    public Optional<OfflineOperationReceipt> receipt(UUID actorId, UUID operationId) {
        return jdbc.query("""
                SELECT operation_type, state, payload_hash, reason_code, confirmed_at, result_json
                FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?
                """, (rs, row) -> {
                    String result = rs.getString("result_json");
                    return new OfflineOperationReceipt(operationId,
                            OfflineOperationReceipt.Type.valueOf(rs.getString("operation_type")),
                            OfflineOperationReceipt.State.valueOf(rs.getString("state")),
                            rs.getString("payload_hash"), rs.getString("reason_code"),
                            rs.getObject("confirmed_at", OffsetDateTime.class),
                            result == null ? null : JSON.readValue(result, OfflineOperationReceipt.Result.class));
                }, actorId, operationId).stream().findFirst();
    }
}
