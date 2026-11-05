-- Match the due-row lock scan without indexing terminal rejection history.
CREATE INDEX idx_rechazos_facturacion_pending_dispatch
    ON rechazos_facturacion (next_attempt_at, event_id)
    WHERE estado = 'PENDIENTE';
