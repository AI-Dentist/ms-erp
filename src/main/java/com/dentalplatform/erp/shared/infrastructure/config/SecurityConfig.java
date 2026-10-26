package com.dentalplatform.erp.shared.infrastructure.config;

import com.dentalplatform.erp.shared.infrastructure.security.DevUserHeaderAuthenticationFilter;
import com.dentalplatform.erp.shared.infrastructure.security.KeycloakRealmRoleConverter;
import com.dentalplatform.erp.shared.infrastructure.security.LazyIssuerJwtDecoder;
import com.dentalplatform.erp.shared.infrastructure.security.LocalDevAuthGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

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

    /** Startup guard: local X-Dev-User mode only with the {@code local} profile, never with legacy dev-mode or prod. */
    @Bean
    static LocalDevAuthGuard localDevAuthGuard(Environment environment,
                                               @Value("${erp.security.local-dev-user.enabled:false}") boolean enabled,
                                               @Value("${erp.security.dev-mode:false}") boolean legacyDevMode) {
        return new LocalDevAuthGuard(environment, enabled, legacyDevMode);
    }

    /**
     * Profile {@code local} only: frontend integration. {@code X-Dev-User} is resolved to an ACTIVE database user whose
     * single role is granted, so method security still applies. No header (and no valid Bearer) means 401; there is
     * no anonymous all-roles fallback. Bearer JWTs are validated against {@code erp.security.local-dev-user.jwt-issuer-uri}
     * (local Keycloak by default, blank disables them); the issuer is resolved lazily so startup never needs Keycloak.
     * A Bearer request never falls back to {@code X-Dev-User}.
     */
    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "erp.security.local-dev-user.enabled", havingValue = "true")
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http, JdbcTemplate jdbc,
                                                 @Value("${erp.security.local-dev-user.jwt-issuer-uri:}") String jwtIssuer)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/pagofacil").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new DevUserHeaderAuthenticationFilter(jdbc), AnonymousAuthenticationFilter.class);
        if (!jwtIssuer.isBlank()) {
            JwtDecoder decoder = new LazyIssuerJwtDecoder(jwtIssuer.trim());
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)
                    .jwtAuthenticationConverter(jwtConverter())));
        }
        return http.build();
    }

    private static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    /** Production: stateless OAuth2 resource server validating Keycloak JWTs. */
    @Bean
    @ConditionalOnExpression("!'${erp.security.dev-mode:false}'.equals('true') "
            + "&& !'${erp.security.local-dev-user.enabled:false}'.equals('true')")
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = jwtConverter();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Local mock callback authenticates its own secret on every call; real provider is unsupported.
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
