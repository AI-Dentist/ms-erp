package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.financebilling.application.agent.*;
import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class BusinessHealthToolTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    private MovimientoCaja expense(String amount) {
        return MovimientoCaja.crear(null, UUID.randomUUID(), TipoMovimientoCaja.EGRESO, "TEST", MetodoPago.EFECTIVO,
                Money.of(amount), "Synthetic expense", OffsetDateTime.now(clock));
    }
    private ErpMcpToolsService tool(List<MovimientoCaja> today, List<MovimientoCaja> history) {
        var monitoring = mock(MonitoreoNegocioUseCase.class);
        when(monitoring.cuotasVencidas(any())).thenReturn(List.of());
        var finances = mock(ConsultarFinanzasUseCase.class);
        when(finances.obtenerFlujoCaja(any(), any())).thenReturn(
                new ConsultarFinanzasUseCase.ResumenFlujoCaja(BigDecimal.ZERO, new BigDecimal("40.01"), new BigDecimal("-40.01"), today),
                new ConsultarFinanzasUseCase.ResumenFlujoCaja(BigDecimal.ZERO, new BigDecimal("20"), new BigDecimal("-20"), history));
        return new ErpMcpToolsService(monitoring, finances, null, null, null, null, null, null, null, new AgentToolTrace(), clock, null);
    }
    @Test
    void onlyExpensesStrictlyAboveTwiceTheHistoricalMeanAreFlagged() {
        var equal = expense("20.00");
        var above = expense("20.01");
        var result = tool(List.of(equal, above), List.of(expense("5"), expense("15"))).auditarSaludNegocio();
        assertThat(result.umbralEgresoInusualBs()).isEqualByComparingTo("20.00");
        assertThat(result.egresosHistoricos()).isEqualTo(2);
        assertThat(result.egresosInusuales()).extracting(ErpMcpToolsService.EgresoInusualTool::movimientoId).containsExactly(above.id());
        assertThat(result.diagnostico()).contains("2x promedio", "Bs -40.01");
        assertThat(result.accionesRecomendadas()).anyMatch(s -> s.contains("comprobantes")).anyMatch(s -> s.contains("liquidez"));
    }
    @Test
    void fractionalMeanDoesNotRoundAwayARealAnomaly() {
        var above = expense("20.67");
        var result = tool(List.of(above), List.of(expense("10"), expense("10"), expense("11"))).auditarSaludNegocio();
        assertThat(result.egresosInusuales()).extracting(ErpMcpToolsService.EgresoInusualTool::movimientoId).containsExactly(above.id());
    }
    @Test
    void noPriorHistoryDoesNotInventAnomalyThresholdOrAnomalies() {
        var result = tool(List.of(expense("100000")), List.of()).auditarSaludNegocio();
        assertThat(result.umbralEgresoInusualBs()).isNull();
        assertThat(result.egresosInusuales()).isEmpty();
        assertThat(result.egresosHistoricos()).isZero();
        assertThat(result.diagnostico()).contains("Sin histórico previo");
    }
}
