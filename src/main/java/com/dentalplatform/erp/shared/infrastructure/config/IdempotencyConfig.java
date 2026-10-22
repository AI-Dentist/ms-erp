package com.dentalplatform.erp.shared.infrastructure.config;

import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyFilter;
import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyStore;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class IdempotencyConfig {

    /** Runs after the Spring Security chain (order -100) so unauthenticated calls never consume a key. */
    static final int FILTER_ORDER = 0;

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, Clock clock,
                                                                @Value("${erp.idempotency.ttl-hours:24}") long ttlHours) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyFilter(store, Duration.ofHours(ttlHours), clock));
        registration.setOrder(FILTER_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.addUrlPatterns("/graphql", "/api/*");
        return registration;
    }
}
