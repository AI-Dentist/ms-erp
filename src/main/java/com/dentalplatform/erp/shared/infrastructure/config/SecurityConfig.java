package com.dentalplatform.erp.shared.infrastructure.config;

import com.dentalplatform.erp.shared.infrastructure.security.KeycloakRealmRoleConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    public static final String DEV_USERNAME = "admin";

    /**
     * Local profile ({@code erp.security.dev-mode=true}): no Keycloak needed; every request runs as the seeded
     * admin with all roles so method security still applies.
     */
    @Bean
    @ConditionalOnProperty(name = "erp.security.dev-mode", havingValue = "true")
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .anonymous(anonymous -> anonymous
                        .principal(DEV_USERNAME)
                        .authorities(AuthorityUtils.createAuthorityList(
                                "ROLE_ADMIN", "ROLE_CAJERO", "ROLE_RECEPCIONISTA", "ROLE_ODONTOLOGO")))
                .build();
    }

    /** Production: stateless OAuth2 resource server validating Keycloak JWTs. */
    @Bean
    @ConditionalOnProperty(name = "erp.security.dev-mode", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("preferred_username");
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // PagoFacil cannot send a Keycloak token; the webhook verifies its own shared secret
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/pagofacil").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
