-- One row per collection reminder (n8n/WhatsApp) sent for an installment. It is the dedup source: the same
-- installment is never reminded twice inside the 24h window, and the row is inserted atomically before sending.
CREATE TABLE cobranza_recordatorios (
    id          UUID PRIMARY KEY,
    -- Immutable historical identifier, deliberately not an FK: an unpaid plan replaces its installment rows.
    -- Keep the reminder/dedup evidence after replacement rather than blocking the plan or cascading history deletion.
    cuota_id    UUID         NOT NULL,
    usuario_id  UUID REFERENCES usuarios (id),
    solicitante VARCHAR(150) NOT NULL,
    enviado_en  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_cobranza_recordatorios_cuota ON cobranza_recordatorios (cuota_id, enviado_en DESC);
