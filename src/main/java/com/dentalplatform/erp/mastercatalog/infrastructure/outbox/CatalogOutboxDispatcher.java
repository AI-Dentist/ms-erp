package com.dentalplatform.erp.mastercatalog.infrastructure.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** One small transaction per event. SKIP LOCKED supports independent processes without a lease/recovery race. */
@Component
public class CatalogOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final ConfirmedCatalogSender sender;
    private final CatalogOutboxSettings settings;
    private final TransactionTemplate transaction;

    public CatalogOutboxDispatcher(JdbcTemplate jdbc, ConfirmedCatalogSender sender, CatalogOutboxSettings settings,
                                   PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.settings = settings;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatch() {
        for (int i = 0; i < settings.batchSize(); i++) {
            Boolean found = transaction.execute(status -> {
                var rows = jdbc.query("""
                        SELECT event_id, exchange_name, routing_key, payload::text, attempts
                        FROM catalog_event_outbox
                        WHERE sent_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                        ORDER BY next_attempt_at, created_at, event_id
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                        """, (rs, index) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getInt(5)));
                if (rows.isEmpty()) return false;
                Pending row = rows.getFirst();
                int attempts = row.attempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : row.attempts() + 1;
                boolean delivered = sender.send(row.id(), row.exchange(), row.routingKey(), row.payload());
                jdbc.update("""
                        UPDATE catalog_event_outbox SET attempts = ?,
                        sent_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                        next_attempt_at = clock_timestamp() + (? * INTERVAL '1 millisecond')
                        WHERE event_id = ?
                        """, attempts, delivered, settings.backoffMs(attempts), row.id());
                return true;
            });
            if (!Boolean.TRUE.equals(found)) break;
        }
    }

    private record Pending(UUID id, String exchange, String routingKey, String payload, int attempts) { }
}
