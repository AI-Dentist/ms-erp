CREATE TABLE offline_operation_receipts (
    actor_id UUID NOT NULL REFERENCES usuarios(id),
    operation_id UUID NOT NULL,
    identity_issuer TEXT NOT NULL,
    identity_subject TEXT NOT NULL,
    operation_type VARCHAR(24) NOT NULL CHECK
        (operation_type IN ('CASH_PAYMENT', 'CASH_EXPENSE', 'MANUAL_ORDER', 'PATIENT_CREATE')),
    canonical_version SMALLINT NOT NULL DEFAULT 1 CHECK (canonical_version = 1),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PREPARED', 'CONFIRMED', 'CONFLICT', 'BLOCKED')),
    reason_code VARCHAR(64),
    confirmed_at TIMESTAMPTZ,
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, operation_id),
    CHECK (
        (state = 'CONFIRMED' AND confirmed_at IS NOT NULL AND result_json IS NOT NULL AND reason_code IS NULL)
        OR (state <> 'CONFIRMED' AND confirmed_at IS NULL AND result_json IS NULL)
    ),
    CHECK (state NOT IN ('CONFLICT', 'BLOCKED') OR reason_code IS NOT NULL)
);
