-- ERP copilot agent history. The instruction is stored truncated (500 chars) and blocked prompt-injection
-- attempts are stored WITHOUT their text, so a pasted secret never lands here.
CREATE TABLE agente_interacciones (
    id            UUID PRIMARY KEY,
    usuario_id    UUID REFERENCES usuarios (id),
    solicitante   VARCHAR(150) NOT NULL,
    fecha         TIMESTAMPTZ  NOT NULL,
    instruccion   VARCHAR(500) NOT NULL,
    respuesta     TEXT         NOT NULL,
    tools         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    accion_crud   VARCHAR(200),
    n8n_enviada   BOOLEAN      NOT NULL DEFAULT FALSE,
    reporte_id    UUID,
    modo          VARCHAR(20)  NOT NULL CHECK (modo IN ('IA', 'RESPALDO')),
    bloqueada     BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_agente_interacciones_fecha ON agente_interacciones (fecha DESC);
CREATE INDEX idx_agente_interacciones_solicitante ON agente_interacciones (solicitante, fecha DESC);
