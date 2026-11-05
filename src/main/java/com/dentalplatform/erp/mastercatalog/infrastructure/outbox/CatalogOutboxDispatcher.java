package com.dentalplatform.erp.mastercatalog.infrastructure.outbox;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/** One small transaction per event. Terminal rows are retained and never selected automatically again. */
@Component
public class CatalogOutboxDispatcher {
    private static final String UNCONFIRMED = "PUBLICACION_NO_CONFIRMADA";
    private final JdbcTemplate jdbc;
    private final ConfirmedCatalogSender sender;
    private final CatalogOutboxSettings settings;
    private final RegistrarAuditoriaUseCase auditoria;
    private final TransactionTemplate transaction;

    public CatalogOutboxDispatcher(JdbcTemplate jdbc, ConfirmedCatalogSender sender, CatalogOutboxSettings settings,
                                   PlatformTransactionManager manager, RegistrarAuditoriaUseCase auditoria) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.settings = settings;
        this.auditoria = auditoria;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatch() {
        for (int i = 0; i < settings.batchSize(); i++) {
            Boolean found = transaction.execute(status -> {
                var rows = jdbc.query("""
                        SELECT event_id, exchange_name, routing_key, payload::text, attempts
                        FROM catalog_event_outbox
                        WHERE estado = 'PENDIENTE' AND sent_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                        ORDER BY next_attempt_at, created_at, event_id
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """, (rs, index) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getInt(5)));
                if (rows.isEmpty()) return false;
                Pending row = rows.getFirst();
                // Lowering the configured limit also terminalizes previously exhausted rows, without another send.
                boolean eligible = row.attempts() < settings.maxAttempts();
                int attempts = eligible ? row.attempts() + 1 : row.attempts();
                boolean delivered = eligible && sender.send(row.id(), row.exchange(), row.routingKey(), row.payload());
                boolean terminal = !delivered && attempts >= settings.maxAttempts();
                String state = delivered ? "ENVIADO" : terminal ? "FALLIDO" : "PENDIENTE";
                jdbc.update("""
                        UPDATE catalog_event_outbox SET attempts = ?, estado = ?, last_error = ?,
                        sent_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                        next_attempt_at = clock_timestamp() + (? * INTERVAL '1 millisecond')
                        WHERE event_id = ?
                        """, attempts, state, delivered ? null : UNCONFIRMED,
                        delivered, settings.backoffMs(attempts), row.id());
                if (terminal) {
                    // Same transaction as FALLIDO; never include payload or transport exception text.
                    auditoria.registrar("CATALOGO_EVENTO_PUBLICACION_FALLIDA", "catalog_event_outbox", row.id(),
                            Map.of("attempts", attempts, "estado", state, "motivo", UNCONFIRMED,
                                    "routingKey", row.routingKey()));
                }
                return true;
            });
            if (!Boolean.TRUE.equals(found)) break;
        }
    }

    private record Pending(UUID id, String exchange, String routingKey, String payload, int attempts) { }
}
