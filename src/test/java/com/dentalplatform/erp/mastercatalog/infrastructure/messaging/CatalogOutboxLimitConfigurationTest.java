package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.mastercatalog.infrastructure.outbox.CatalogOutboxSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CatalogOutboxLimitConfigurationTest {
    private AnnotationConfigApplicationContext context(int attempts) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",
                Map.of("erp.catalog-outbox.max-attempts", attempts)));
        context.register(CatalogOutboxSettings.class);
        return context;
    }

    @ParameterizedTest @ValueSource(ints = {0, 11, Integer.MAX_VALUE})
    void invalidAttemptLimitFailsStartup(int attempts) {
        try (var context = context(attempts)) {
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 10})
    void inclusiveBoundariesPreserveExistingResourceDefaults(int attempts) {
        try (var context = context(attempts)) {
            context.refresh();
            var settings = context.getBean(CatalogOutboxSettings.class);
            assertThat(settings.batchSize()).isEqualTo(1);
            assertThat(settings.sendTimeoutMs()).isEqualTo(2000);
            assertThat(settings.backoffMs(1)).isEqualTo(1000);
        }
    }
}
