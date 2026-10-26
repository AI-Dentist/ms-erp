-- Retain sent rows; event_id is also the consumer deduplication key.
CREATE TABLE catalog_event_outbox (
    event_id UUID PRIMARY KEY,
    exchange_name TEXT NOT NULL,
    routing_key TEXT NOT NULL,
    payload JSONB NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CHECK (payload->>'eventId' IS NOT NULL AND payload->>'eventId' = event_id::text)
);

CREATE INDEX catalog_event_outbox_due ON catalog_event_outbox (next_attempt_at, created_at, event_id)
    WHERE sent_at IS NULL;
