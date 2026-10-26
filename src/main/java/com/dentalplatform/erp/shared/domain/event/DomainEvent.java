package com.dentalplatform.erp.shared.domain.event;

import java.util.UUID;

/**
 * Marker for events that cross bounded-context or service boundaries.
 */
public interface DomainEvent {

    /** Unique id so consumers can deduplicate redeliveries. */
    UUID eventId();

    /** RabbitMQ routing key of the event. */
    String routingKey();
}
