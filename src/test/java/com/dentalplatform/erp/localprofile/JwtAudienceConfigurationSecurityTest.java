package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.shared.infrastructure.config.SecurityConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtAudienceConfigurationSecurityTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankNormalAudienceCannotStartWithoutValidation(String audience) {
        rejectsBlankConfiguration(audience, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankLocalAudienceCannotStartWithoutValidation(String audience) {
        rejectsBlankConfiguration(audience, true);
    }

    private void rejectsBlankConfiguration(String audience, boolean local) {
        new WebApplicationContextRunner().withUserConfiguration(Boundary.class)
                .withInitializer(context -> {
                    if (local) context.getEnvironment().setActiveProfiles("local");
                })
                .withPropertyValues("erp.security.dev-mode=false",
                        "erp.security.local-dev-user.enabled=" + local,
                        "erp.security.local-dev-user.jwt-issuer-uri=http://127.0.0.1:1/synthetic",
                        "erp.security.jwt.audience=" + audience)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("JWT audience must not be blank");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Boundary {
        // Startup only: no requests/issuer calls. Public HTTP matrices use real signed tokens and decoders.
        @Bean JwtDecoder jwtDecoder() { return token -> { throw new BadJwtException("Unused startup fixture"); }; }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
    }
}
