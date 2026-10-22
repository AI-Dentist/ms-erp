package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Enqueues atomically with the aggregate. REQUIRED also supports standalone callers without broker I/O. */
@Component
public class RabbitMQCatalogEventPublisher implements CatalogEventPublisherPort {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String exchange;

    public RabbitMQCatalogEventPublisher(JdbcTemplate jdbc, JsonMapper json,
                                         @Value("${erp.messaging.exchange}") String exchange) {
        this.jdbc = jdbc;
        this.json = json;
        this.exchange = exchange;
    }

    @Override
    @Transactional
    public void publicar(DomainEvent event) {
        // Never suppress serialization or SQL failures: the creating transaction must roll back too.
        jdbc.update("""
                INSERT INTO catalog_event_outbox(event_id, exchange_name, routing_key, payload)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, event.eventId(), exchange, event.routingKey(), json.writeValueAsString(event));
    }
}
