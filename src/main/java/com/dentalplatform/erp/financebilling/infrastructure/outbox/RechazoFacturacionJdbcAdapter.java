package com.dentalplatform.erp.financebilling.infrastructure.outbox;

import com.dentalplatform.erp.financebilling.application.port.RechazoFacturacionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class RechazoFacturacionJdbcAdapter implements RechazoFacturacionPort {
    private final JdbcTemplate jdbc;

    public RechazoFacturacionJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean existePorCierre(UUID closure) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM rechazos_facturacion WHERE consulta_finalizada_event_id = ?)
                """, Boolean.class, closure));
    }

    @Override
    public boolean insertarSiAusente(Rechazo r) {
        return jdbc.update("""
                INSERT INTO rechazos_facturacion
                    (event_id, consulta_id, consulta_finalizada_event_id, motivo_codigo, motivo, rechazada_en,
                     estado, attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDIENTE', 0, ?)
                ON CONFLICT (consulta_finalizada_event_id) DO NOTHING
                """, r.eventId(), r.consultaId(), r.consultaFinalizadaEventId(), r.motivoCodigo(),
                r.motivo(), r.rechazadaEn(), r.rechazadaEn()) == 1;
    }
}
