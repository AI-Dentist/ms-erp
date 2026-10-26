package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.financebilling.application.agent.DeterministicReportTemplates;
import com.dentalplatform.erp.financebilling.domain.report.SqlReadOnlyGuardrail;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicReportTemplatesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Test
    void unfilteredSupportedIntentsKeepTheirExistingSafeTemplates() {
        var guard = new SqlReadOnlyGuardrail();
        for (String request : List.of("reporte de precios de servicios", "reporte de ordenes por estado",
                "reporte de ingresos por categoria", "reporte de ingresos por metodo de pago", "reporte diario de ingresos")) {
            String sql = DeterministicReportTemplates.resolver(request, TODAY);
            assertThat(guard.validate(sql)).isEqualTo(sql);
        }
    }

    @Test
    void explicitPaymentFilterSurvivesEveryIncomeGrouping() {
        for (String grouping : List.of("categoria", "metodo de pago", "dia")) {
            String sql = DeterministicReportTemplates.resolver("Genera un reporte de ingresos por " + grouping + " solo QR_PAGOFACIL", TODAY);
            assertThat(sql).contains("WHERE payment_method = 'QR_PAGOFACIL'");
            assertThat(new SqlReadOnlyGuardrail().validate(sql)).isEqualTo(sql);
        }
    }

    @Test
    void unsupportedExplicitConstraintsAreRejectedNotSilentlyBroadened() {
        for (String request : List.of("reporte de ingresos de esta semana", "reporte de ingresos desde 2026-01-01",
                "reporte de ingresos categoria COBRO_CUOTA", "reporte de servicios inactivos",
                "reporte de ordenes pendientes", "reporte de ingresos solo QR y EFECTIVO",
                "reporte de ingresos mayores a 100", "reporte de ingresos por dia solo")) {
            assertThatThrownBy(() -> DeterministicReportTemplates.resolver(request, TODAY))
                    .as(request).isInstanceOf(BusinessRuleException.class).hasMessageContaining("no puede representar todos los filtros");
        }
    }
}
