package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.financebilling.application.port.MultichannelNotificationPort;
import com.dentalplatform.erp.financebilling.application.usecase.DispararNotificacionMulticanalUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultichannelExecutionPolicyTest {
    @Test void disabledDevAndLocalSimulateButProductionAndUnknownProfilesFailClosed() {
        for (String profile : List.of("dev", "local", "prod", "production", "default")) {
            var port = mock(MultichannelNotificationPort.class);
            var environment = new MockEnvironment(); environment.setActiveProfiles(profile);
            var result = execute(new DispararNotificacionMulticanalUseCase(port, environment));
            boolean demo = profile.equals("dev") || profile.equals("local");
            assertThat(result.exito()).as(profile).isEqualTo(demo);
            assertThat(result.modoEjecucion()).isEqualTo(demo ? "SIMULADO_SIN_N8N" : "NO_ENVIADO");
            assertThat(result.resultadosPorCanal()).allSatisfy(row -> assertThat(row.estado()).isEqualTo(demo ? "SIMULADO_OK" : "NO_ENVIADO"));
            verify(port, never()).enviar(any());
        }
    }
    @Test void productionNeverTurnsAConnectionFailureIntoSuccessEvenWithLocalProfilePresent() {
        var port = mock(MultichannelNotificationPort.class); when(port.habilitado()).thenReturn(true);
        doThrow(new MultichannelNotificationPort.UnavailableException(new ConnectException("Synthetic failure"))).when(port).enviar(any());
        var environment = new MockEnvironment(); environment.setActiveProfiles("local", "production");
        var result = execute(new DispararNotificacionMulticanalUseCase(port, environment));
        assertThat(result.exito()).isFalse(); assertThat(result.modoEjecucion()).isEqualTo("NO_ENVIADO");
        assertThat(result.resultadosPorCanal()).allSatisfy(row -> assertThat(row.estado()).isEqualTo("NO_ENVIADO"));
        verify(port, times(1)).enviar(any());
    }
    @Test void explicitHttpRejectionIsNotSimulatedAsASuccessEvenInDev() {
        var port = mock(MultichannelNotificationPort.class); when(port.habilitado()).thenReturn(true);
        doThrow(new IllegalStateException("Synthetic HTTP rejection")).when(port).enviar(any());
        var environment = new MockEnvironment(); environment.setActiveProfiles("dev");
        assertThat(execute(new DispararNotificacionMulticanalUseCase(port, environment)).exito()).isFalse();
        verify(port, times(1)).enviar(any());
    }
    private com.dentalplatform.erp.financebilling.application.agent.NotificacionMulticanalPayload execute(DispararNotificacionMulticanalUseCase useCase) {
        return useCase.ejecutar("Synthetic patient", "+59170012345", null, null, new BigDecimal("350"),
                "Synthetic concept", "2026-11-05", "TODOS");
    }
}
