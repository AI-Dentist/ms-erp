package com.dentalplatform.erp.shared.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Request handling, @Async and RabbitMQ listeners run on virtual threads via
 * {@code spring.threads.virtual.enabled=true}; this class enables async/scheduling on top of it.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class VirtualThreadConfig {

    public static final ZoneId BOLIVIA = ZoneId.of("America/La_Paz");

    @Bean
    Clock clock() {
        return Clock.system(BOLIVIA);
    }
}
