package com.dentalplatform.erp.shared.infrastructure.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Fail-closed startup guard for the local X-Dev-User mode: refuses to start when the mode is enabled outside the
 * {@code local} profile or next to the legacy {@code erp.security.dev-mode}, and when {@code local} is combined
 * or {@code dev} is combined with a production profile (enabled or not). Legacy dev mode is also forbidden
 * in production.
 */
public class LocalDevAuthGuard implements InitializingBean {

    private static final Profiles PRODUCTION = Profiles.of("prod", "production");

    private final Environment environment;
    private final boolean localDevUserEnabled;
    private final boolean legacyDevMode;

    public LocalDevAuthGuard(Environment environment, boolean localDevUserEnabled, boolean legacyDevMode) {
        this.environment = environment;
        this.localDevUserEnabled = localDevUserEnabled;
        this.legacyDevMode = legacyDevMode;
    }

    @Override
    public void afterPropertiesSet() {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        boolean production = environment.acceptsProfiles(PRODUCTION);
        if (production && local) {
            throw new IllegalStateException("The local profile must never be combined with a production profile");
        }
        if (production && environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("The dev profile must never be combined with a production profile");
        }
        if (production && legacyDevMode) {
            throw new IllegalStateException("erp.security.dev-mode=true must never be enabled in production");
        }
        if (localDevUserEnabled && !local) {
            throw new IllegalStateException("erp.security.local-dev-user.enabled requires the local profile");
        }
        if (localDevUserEnabled && legacyDevMode) {
            throw new IllegalStateException(
                    "erp.security.local-dev-user.enabled cannot be combined with erp.security.dev-mode=true");
        }
    }
}
