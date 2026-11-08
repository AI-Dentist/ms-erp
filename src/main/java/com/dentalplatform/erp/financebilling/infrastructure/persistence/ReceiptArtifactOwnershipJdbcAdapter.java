package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.application.port.ReceiptArtifactOwnershipPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
class ReceiptArtifactOwnershipJdbcAdapter implements ReceiptArtifactOwnershipPort {
    private final JdbcTemplate jdbc;

    ReceiptArtifactOwnershipJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bloquearRecibo(UUID receiptId) {
        var rows = jdbc.query("SELECT id FROM recibos WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), receiptId);
        if (rows.isEmpty()) throw new IllegalStateException("Recibo no disponible para el artefacto");
    }

    @Override
    public Optional<String> referenciaReservada(UUID receiptId) {
        var rows = jdbc.query("""
                SELECT detalle_json->>'referencia' FROM audit_logs
                WHERE accion = 'RECIBO_PDF_RESERVADO' AND entidad_afectada = 'recibos' AND entidad_id = ?
                ORDER BY created_at, id LIMIT 1
                """, (rs, n) -> rs.getString(1), receiptId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
    }
}
