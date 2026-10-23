package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.AgenteInteraccion;
import com.dentalplatform.erp.financebilling.domain.repository.AgenteInteraccionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Own short transaction per call so blocked/failed attempts stay recorded even when the request then fails. */
@Component
class AgenteInteraccionJdbcAdapter implements AgenteInteraccionRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AgenteInteraccionJdbcAdapter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public AgenteInteraccion guardar(AgenteInteraccion i) {
        jdbc.update("""
                INSERT INTO agente_interacciones(id, usuario_id, solicitante, fecha, instruccion, respuesta, tools,
                                                 accion_crud, n8n_enviada, reporte_id, modo, bloqueada)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, i.id(), i.usuarioId(), i.solicitante(), i.fecha(), i.instruccion(), i.respuesta(),
                json.writeValueAsString(i.tools()), i.accionCrud(), i.n8nEnviada(), i.reporteId(), i.modo(),
                i.bloqueada());
        return i;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgenteInteraccion> listarRecientes(String solicitante, int limite) {
        String filter = solicitante == null ? "" : "WHERE solicitante = ? ";
        Object[] args = solicitante == null ? new Object[]{limite} : new Object[]{solicitante, limite};
        return jdbc.query("SELECT id, usuario_id, solicitante, fecha, instruccion, respuesta, tools::text tools, "
                + "accion_crud, n8n_enviada, reporte_id, modo, bloqueada FROM agente_interacciones " + filter
                + "ORDER BY fecha DESC, id LIMIT ?", (rs, n) -> new AgenteInteraccion(
                rs.getObject("id", UUID.class), rs.getObject("usuario_id", UUID.class), rs.getString("solicitante"),
                rs.getObject("fecha", OffsetDateTime.class), rs.getString("instruccion"), rs.getString("respuesta"),
                json.readValue(rs.getString("tools"), new TypeReference<List<String>>() { }),
                rs.getString("accion_crud"), rs.getBoolean("n8n_enviada"), rs.getObject("reporte_id", UUID.class),
                rs.getString("modo"), rs.getBoolean("bloqueada")), args);
    }
}
