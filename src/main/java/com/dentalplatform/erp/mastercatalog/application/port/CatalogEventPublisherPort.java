package com.dentalplatform.erp.mastercatalog.application.port;

import com.dentalplatform.erp.shared.domain.event.DomainEvent;

/**
 * Outgoing port that announces catalog changes to the other microservices. Implementations must deliver only
 * once the surrounding transaction has committed.
 */
public interface CatalogEventPublisherPort {

    void publicar(DomainEvent event);
}
