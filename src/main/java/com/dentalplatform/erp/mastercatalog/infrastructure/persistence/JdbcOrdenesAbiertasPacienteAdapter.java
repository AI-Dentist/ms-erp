package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import com.dentalplatform.erp.mastercatalog.application.port.OrdenesAbiertasPacientePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Reads the billing table directly so the catalog context does not depend on billing internals. */
@Component
class JdbcOrdenesAbiertasPacienteAdapter implements OrdenesAbiertasPacientePort {

    private final JdbcTemplate jdbc;

    JdbcOrdenesAbiertasPacienteAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long contar(UUID pacienteId) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM ordenes_cobro WHERE paciente_id = ? AND estado IN ('PENDIENTE', 'PARCIAL')",
                Long.class, pacienteId);
        return total == null ? 0 : total;
    }
}
