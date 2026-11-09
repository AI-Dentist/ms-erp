package com.dentalplatform.erp.production;

import com.dentalplatform.erp.shared.infrastructure.security.LocalDevAuthGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdDevModeGuardTest {
    @Test
    void localAndProdRetainExistingStartupError() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> new LocalDevAuthGuard(environment, false, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The local profile must never be combined with a production profile");
    }

    @Test
    void legacyDevBypassIsRejectedInProd() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> new LocalDevAuthGuard(environment, false, true).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionAliasesRejectDevProfileAndLegacyBypassButPreserveDevelopmentModes() {
        for (String prod : new String[] {"prod", "production"}) {
            for (String development : new String[] {"dev", "local"}) {
                var environment = new MockEnvironment();
                environment.setActiveProfiles(prod, development);
                assertThatThrownBy(() -> new LocalDevAuthGuard(environment, false, false).afterPropertiesSet())
                        .isInstanceOf(IllegalStateException.class);
            }
            var environment = new MockEnvironment();
            environment.setActiveProfiles(prod);
            assertThatThrownBy(() -> new LocalDevAuthGuard(environment, false, true).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class);
            assertThatCode(() -> new LocalDevAuthGuard(environment, false, false).afterPropertiesSet())
                    .doesNotThrowAnyException();
        }
        var dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> new LocalDevAuthGuard(dev, false, true).afterPropertiesSet()).doesNotThrowAnyException();
        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new LocalDevAuthGuard(local, true, false).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
