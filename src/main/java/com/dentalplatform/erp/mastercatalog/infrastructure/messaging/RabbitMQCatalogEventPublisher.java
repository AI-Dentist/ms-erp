package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.shared.domain.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes catalog events to the topic exchange only after the surrounding transaction commits, so consumers never
 * learn about rows that were rolled back. Without an active transaction the event is sent immediately.
 */
@Component
public class RabbitMQCatalogEventPublisher implements CatalogEventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQCatalogEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public RabbitMQCatalogEventPublisher(RabbitTemplate rabbitTemplate,
                                         @Value("${erp.messaging.exchange}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    @Override
    public void publicar(DomainEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviar(event);
                }
            });
        } else {
            enviar(event);
        }
    }

    private void enviar(DomainEvent event) {
        try {
            rabbitTemplate.convertAndSend(exchange, event.routingKey(), event);
        } catch (AmqpException e) {
            // the data is already committed; do not turn a successful request into a failure
            log.error("Could not publish {} ({}) to {}", event.routingKey(), event.eventId(), exchange, e);
        }
    }
}
