-- A rejection and its pending compensation are one durable row; no external MS2 foreign key.
CREATE TABLE rechazos_facturacion (
    event_id UUID PRIMARY KEY,
    consulta_id UUID NOT NULL,
    consulta_finalizada_event_id UUID NOT NULL UNIQUE,
    motivo_codigo VARCHAR(32) NOT NULL CHECK (motivo_codigo IN (
        'PACIENTE_INEXISTENTE', 'ODONTOLOGO_INEXISTENTE', 'SERVICIO_INEXISTENTE',
        'SERVICIO_INACTIVO', 'MONTO_INVALIDO', 'PAYLOAD_INVALIDO', 'REINTENTOS_AGOTADOS'
    )),
    motivo VARCHAR(500) NOT NULL CHECK (length(motivo) BETWEEN 1 AND 500),
    rechazada_en TIMESTAMPTZ NOT NULL,
    estado VARCHAR(10) NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'FALLIDO')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(500),
    sent_at TIMESTAMPTZ
);
