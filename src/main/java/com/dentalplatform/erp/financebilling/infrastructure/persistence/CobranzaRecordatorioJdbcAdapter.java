package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.application.port.CobranzaRecordatorioPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The advisory transaction lock serializes concurrent reminders of the same installment across instances. */
@Component
class CobranzaRecordatorioJdbcAdapter implements CobranzaRecordatorioPort {
    private final JdbcTemplate jdbc;

    CobranzaRecordatorioJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public boolean reservar(UUID cuotaId, UUID usuarioId, String solicitante, OffsetDateTime ahora, Duration ventana) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "cobranza:" + cuotaId);
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM cobranza_recordatorios WHERE cuota_id = ? AND enviado_en > ?",
                Integer.class, cuotaId, ahora.minus(ventana));
        if (recent != null && recent > 0) { return false; }
        jdbc.update("INSERT INTO cobranza_recordatorios(id, cuota_id, usuario_id, solicitante, enviado_en) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), cuotaId, usuarioId, solicitante, ahora);
        return true;
    }

    @Override
    @Transactional
    public void liberar(UUID cuotaId, OffsetDateTime ahora) {
        jdbc.update("DELETE FROM cobranza_recordatorios WHERE cuota_id = ? AND enviado_en = ?", cuotaId, ahora);
    }
}
