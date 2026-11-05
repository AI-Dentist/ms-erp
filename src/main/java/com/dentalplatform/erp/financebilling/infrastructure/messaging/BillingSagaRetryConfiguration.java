package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.LongConsumer;

@Configuration(proxyBeanMethods = false)
public class BillingSagaRetryConfiguration {
    @Bean
    public static LongConsumer billingSagaRetryPause() {
        return delay -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ImmediateRequeueAmqpException("Billing saga retry interrupted", interrupted);
            }
        };
    }
}
