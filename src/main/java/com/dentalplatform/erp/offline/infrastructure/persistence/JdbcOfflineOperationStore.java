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
    public Optional<Header> lockHeader(UUID actorId, UUID operationId) {
        return jdbc.query("""
                SELECT identity_issuer, identity_subject, operation_type
                FROM offline_operation_receipts WHERE actor_id = ? AND operation_id = ?
                FOR SHARE
                """, (rs, row) -> new Header(rs.getString("identity_issuer"), rs.getString("identity_subject"),
                OfflineOperationReceipt.Type.valueOf(rs.getString("operation_type"))), actorId, operationId)
                .stream().findFirst();
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
