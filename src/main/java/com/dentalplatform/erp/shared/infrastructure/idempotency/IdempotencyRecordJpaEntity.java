package com.dentalplatform.erp.shared.infrastructure.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecordJpaEntity {

    @Id
    @Column(name = "idempotency_key", length = 100)
    private String key;

    @Column(name = "operation_name", nullable = false, length = 100)
    private String operationName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected IdempotencyRecordJpaEntity() {
    }

    public String getKey() {
        return key;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
