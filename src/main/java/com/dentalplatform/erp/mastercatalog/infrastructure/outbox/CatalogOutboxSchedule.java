package com.dentalplatform.erp.mastercatalog.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "erp.catalog-outbox.enabled", havingValue = "true", matchIfMissing = true)
public class CatalogOutboxSchedule {
    private final CatalogOutboxDispatcher dispatcher;

    public CatalogOutboxSchedule(CatalogOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${erp.catalog-outbox.poll-delay-ms:1000}")
    public void poll() {
        dispatcher.dispatch();
    }
}
