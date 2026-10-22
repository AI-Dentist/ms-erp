package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort;
import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.repository.TransaccionQrRepository;
import com.dentalplatform.erp.financebilling.infrastructure.external.PagoFacilHttpAdapter;
import com.dentalplatform.erp.financebilling.presentation.graphql.PagoFacilGraphQLController;
import com.dentalplatform.erp.financebilling.presentation.rest.PagoFacilWebhookController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QrSafetyGatesTest {
    @Test
    void realProviderNeverSimulatesAndExplainsMissingContract() {
        var adapter = new PagoFacilHttpAdapter(false);
        assertThatThrownBy(adapter::generar).isInstanceOf(UnsupportedPagoFacilIntegrationException.class)
                .hasMessageContaining("protocolo").hasMessageContaining("firma");
        assertThatThrownBy(adapter::exigirMock).isInstanceOf(UnsupportedPagoFacilIntegrationException.class);
    }

    @Test
    void blankAndDefaultSecretsNeverAuthenticateEvenWhenSuppliedExactly() {
        var confirm = mock(ConfirmarPagoQrWebhookUseCase.class);
        var callback = new PagoFacilWebhookController.Callback("MOCK-test", BigDecimal.ONE, OffsetDateTime.now());
        for (String secret : new String[]{"", " ", "change-me", " CHANGE-ME "}) {
            var controller = new PagoFacilWebhookController(confirm, secret);
            assertThat(controller.confirmar(secret, callback).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(new PagoFacilWebhookController(confirm, "valid-local-secret").confirmar(null, callback)
                .getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(confirm);
    }

    @Test
    void simulationRequiresBothDevProfileAndExplicitPropertyAndMock() {
        var confirm = mock(ConfirmarPagoQrWebhookUseCase.class);
        var transactions = mock(TransaccionQrRepository.class);
        var generate = mock(GenerarQrPagoFacilUseCase.class);
        for (boolean dev : new boolean[]{false, true}) {
            for (boolean enabled : new boolean[]{false, true}) {
                if (dev && enabled) { continue; }
                var environment = new MockEnvironment();
                environment.setActiveProfiles(dev ? "dev" : "prod");
                var controller = new PagoFacilGraphQLController(generate, confirm, transactions,
                        new PagoFacilHttpAdapter(true), environment, Clock.systemUTC(), enabled);
                assertThatThrownBy(() -> controller.simularConfirmacionQrDev("MOCK-test"))
                        .hasMessageContaining("perfil dev");
            }
        }
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var controller = new PagoFacilGraphQLController(generate, confirm, transactions,
                new PagoFacilHttpAdapter(false), environment, Clock.systemUTC(), true);
        assertThatThrownBy(() -> controller.simularConfirmacionQrDev("MOCK-test"))
                .isInstanceOf(UnsupportedPagoFacilIntegrationException.class);
        verifyNoInteractions(confirm, transactions, generate);
    }
}
