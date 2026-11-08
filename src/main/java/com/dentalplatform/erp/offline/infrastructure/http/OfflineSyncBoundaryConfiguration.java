package com.dentalplatform.erp.offline.infrastructure.http;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OfflineSyncBoundaryConfiguration {
    @Bean
    FilterRegistrationBean<OfflineSyncBoundaryFilter> offlineSyncBoundary() {
        var registration = new FilterRegistrationBean<>(new OfflineSyncBoundaryFilter());
        registration.setOrder(-1);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.addUrlPatterns("/graphql");
        return registration;
    }
}
