package com.dentalplatform.erp.financebilling.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Persisted audit of one agent execution. {@code modo} is IA (real model) or RESPALDO (deterministic planner). */
public record AgenteInteraccion(UUID id, UUID usuarioId, String solicitante, OffsetDateTime fecha, String instruccion,
                                String respuesta, List<String> tools, String accionCrud, boolean n8nEnviada,
                                UUID reporteId, String modo, boolean bloqueada) {
    public static final int MAX_INSTRUCTION = 500;
    public static final String BLOCKED_PLACEHOLDER = "[instrucción bloqueada por seguridad]";

    public AgenteInteraccion {
        tools = List.copyOf(tools);
        if (instruccion.length() > MAX_INSTRUCTION) { instruccion = instruccion.substring(0, MAX_INSTRUCTION); }
    }
}
