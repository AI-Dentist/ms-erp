-- Preserve every event ID/payload/counter; derive delivery state from the existing sent marker.
ALTER TABLE catalog_event_outbox
    ADD COLUMN estado VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE'
        CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'FALLIDO')),
    ADD COLUMN last_error TEXT;

UPDATE catalog_event_outbox SET estado = 'ENVIADO' WHERE sent_at IS NOT NULL;

ALTER TABLE catalog_event_outbox
    ADD CONSTRAINT catalog_outbox_delivery_state
        CHECK ((estado = 'ENVIADO') = (sent_at IS NOT NULL));

DROP INDEX catalog_event_outbox_due;
CREATE INDEX catalog_event_outbox_due ON catalog_event_outbox (next_attempt_at, created_at, event_id)
    WHERE estado = 'PENDIENTE';
