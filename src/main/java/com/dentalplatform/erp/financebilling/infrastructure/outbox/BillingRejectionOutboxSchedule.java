package com.dentalplatform.erp.financebilling.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "erp.billing-saga.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class BillingRejectionOutboxSchedule {
    private final BillingRejectionOutboxDispatcher dispatcher;

    public BillingRejectionOutboxSchedule(BillingRejectionOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${erp.billing-saga.outbox.poll-delay-ms:1000}")
    public void poll() {
        dispatcher.dispatch();
    }
}
