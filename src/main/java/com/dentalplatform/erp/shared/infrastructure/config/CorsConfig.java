package com.dentalplatform.erp.shared.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Explicit-origin CORS for the browser frontend. The allow-list is empty by default, in which case nothing is
 * registered and no CORS header is ever emitted. Wildcard origins are refused at startup.
 */
@Configuration
public class CorsConfig {

    static final List<String> METHODS = List.of("GET", "POST", "OPTIONS");
    static final List<String> ALLOWED_HEADERS =
            List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-Dev-User");
    static final List<String> EXPOSED_HEADERS = List.of("Idempotency-Replayed", "Content-Disposition");

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${erp.cors.allowed-origins:}") List<String> allowedOrigins,
            @Value("${erp.cors.max-age-seconds:600}") long maxAgeSeconds) {
        List<String> origins = allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("erp.cors.allowed-origins must list explicit origins, never '*'");
        }
        var source = new UrlBasedCorsConfigurationSource();
        if (origins.isEmpty()) {
            return source;
        }
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(maxAgeSeconds);
        source.registerCorsConfiguration("/graphql", configuration);
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }
}
