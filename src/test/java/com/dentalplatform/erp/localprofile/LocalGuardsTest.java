package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.financebilling.application.usecase.ConfirmarPagoQrWebhookUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.GenerarQrPagoFacilUseCase;
import com.dentalplatform.erp.financebilling.domain.repository.TransaccionQrRepository;
import com.dentalplatform.erp.financebilling.infrastructure.external.PagoFacilHttpAdapter;
import com.dentalplatform.erp.financebilling.presentation.graphql.PagoFacilGraphQLController;
import com.dentalplatform.erp.shared.infrastructure.security.LocalDevAuthGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LocalGuardsTest {

    private static LocalDevAuthGuard guard(boolean enabled, boolean legacy, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new LocalDevAuthGuard(environment, enabled, legacy);
    }

    @Test
    void headerAuthenticationRefusesToStartOutsideLocalOrNextToLegacyDevMode() {
        assertThatThrownBy(guard(true, false)::afterPropertiesSet).hasMessageContaining("requires the local profile");
        assertThatThrownBy(guard(true, false, "dev")::afterPropertiesSet).hasMessageContaining("local profile");
        assertThatThrownBy(guard(true, true, "local")::afterPropertiesSet).hasMessageContaining("dev-mode");
        assertThatThrownBy(guard(true, false, "local", "prod")::afterPropertiesSet).hasMessageContaining("production");
        assertThatThrownBy(guard(false, false, "local", "production")::afterPropertiesSet)
                .hasMessageContaining("production");
    }

    @Test
    void validCombinationsStart() {
        assertThatCode(guard(true, false, "local")::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(guard(false, false)::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(guard(false, true, "dev")::afterPropertiesSet).doesNotThrowAnyException();
    }

    private static boolean simulationDenied(boolean enabled, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        var controller = new PagoFacilGraphQLController(mock(GenerarQrPagoFacilUseCase.class),
                mock(ConfirmarPagoQrWebhookUseCase.class), mock(TransaccionQrRepository.class),
                new PagoFacilHttpAdapter(true), environment, Clock.systemUTC(), enabled);
        try {
            controller.simularConfirmacionQrDev("MOCK-gate");
            return false;
        } catch (RuntimeException e) {
            return e.getMessage().contains("perfil dev o local");
        }
    }

    @Test
    void qrSimulationNeedsPropertyAndDevOrLocalAndNeverProduction() {
        assertThat(simulationDenied(true, "prod")).isTrue();
        assertThat(simulationDenied(true)).isTrue();
        assertThat(simulationDenied(false, "local")).isTrue();
        assertThat(simulationDenied(true, "local", "prod")).isTrue();
        // Allowed combinations pass the gate and fail later (the transaction does not exist), not on the gate.
        assertThat(simulationDenied(true, "local")).isFalse();
        assertThat(simulationDenied(true, "dev")).isFalse();
    }
}
