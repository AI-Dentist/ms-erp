package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.GastoPagadoReadPort;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;

@WithMockUser(roles = "ADMIN")
class GastoPagadoDuplicadosGraphQLTest extends IntegrationTestBase {
    @MockitoBean GastoPagadoReadPort port;

    private void assertIncomplete(UUID patient) {
        graphQl.document("query($id: UUID!) { obtenerGastoPagadoPaciente(pacienteId: $id) { completo totalPagadoBs } }")
                .variable("id", patient.toString()).execute()
                .path("obtenerGastoPagadoPaciente.completo").entity(Boolean.class).isEqualTo(false)
                .path("obtenerGastoPagadoPaciente.totalPagadoBs").valueIsNull();
    }

    @Test
    void duplicateProjectionNeverDoubleCounts() {
        UUID patient = UUID.randomUUID();
        var row = new GastoPagadoReadPort.Evidencia(UUID.randomUUID(), "PAGADO", true, UUID.randomUUID(), "PAGADA",
                BigDecimal.TEN, true, UUID.randomUUID(), "INGRESO", "COBRO_CUOTA", "EFECTIVO", BigDecimal.TEN,
                UUID.randomUUID(), BigDecimal.TEN, true);
        when(port.consultar(patient)).thenReturn(List.of(row, row));
        assertIncomplete(patient);
    }

    @Test
    void distinctQuotasSharingSameMovementIdAreRejected() {
        UUID patient = UUID.randomUUID(), sharedMovement = UUID.randomUUID(), order = UUID.randomUUID();
        var row1 = new GastoPagadoReadPort.Evidencia(order, "PAGADO", true, UUID.randomUUID(), "PAGADA",
                BigDecimal.TEN, true, sharedMovement, "INGRESO", "COBRO_CUOTA", "EFECTIVO", BigDecimal.TEN,
                UUID.randomUUID(), BigDecimal.TEN, true);
        var row2 = new GastoPagadoReadPort.Evidencia(order, "PAGADO", true, UUID.randomUUID(), "PAGADA",
                BigDecimal.TEN, true, sharedMovement, "INGRESO", "COBRO_CUOTA", "EFECTIVO", BigDecimal.TEN,
                UUID.randomUUID(), BigDecimal.TEN, true);
        when(port.consultar(patient)).thenReturn(List.of(row1, row2));
        assertIncomplete(patient);
    }

    @Test
    void distinctQuotasSharingSameReceiptIdAreRejected() {
        UUID patient = UUID.randomUUID(), sharedReceipt = UUID.randomUUID(), order = UUID.randomUUID();
        var row1 = new GastoPagadoReadPort.Evidencia(order, "PAGADO", true, UUID.randomUUID(), "PAGADA",
                BigDecimal.TEN, true, UUID.randomUUID(), "INGRESO", "COBRO_CUOTA", "EFECTIVO", BigDecimal.TEN,
                sharedReceipt, BigDecimal.TEN, true);
        var row2 = new GastoPagadoReadPort.Evidencia(order, "PAGADO", true, UUID.randomUUID(), "PAGADA",
                BigDecimal.TEN, true, UUID.randomUUID(), "INGRESO", "COBRO_CUOTA", "EFECTIVO", BigDecimal.TEN,
                sharedReceipt, BigDecimal.TEN, true);
        when(port.consultar(patient)).thenReturn(List.of(row1, row2));
        assertIncomplete(patient);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"negative", "null", "zero"})
    void unpaidQuotaWithInvalidAmountIsRejected(String kind) {
        UUID patient = UUID.randomUUID(), order = UUID.randomUUID();
        BigDecimal amount = switch (kind) {
            case "negative" -> BigDecimal.valueOf(-50);
            case "zero" -> BigDecimal.ZERO;
            default -> null;
        };
        var row = new GastoPagadoReadPort.Evidencia(order, "PENDIENTE", true, UUID.randomUUID(), "PENDIENTE",
                amount, false, null, null, null, null, null, null, null, false);
        when(port.consultar(patient)).thenReturn(List.of(row));
        assertIncomplete(patient);
    }

    @Test
    void paidQuotaWithMissingPaymentDateEvenIfTimestampsMatchIsRejected() {
        UUID patient = UUID.randomUUID(), order = UUID.randomUUID();
        var row = new GastoPagadoReadPort.Evidencia(order, "PAGADO", true, UUID.randomUUID(), "PAGADA",
                BigDecimal.TEN, false, UUID.randomUUID(), "INGRESO", "COBRO_CUOTA", "EFECTIVO", BigDecimal.TEN,
                UUID.randomUUID(), BigDecimal.TEN, true);
        when(port.consultar(patient)).thenReturn(List.of(row));
        assertIncomplete(patient);
    }

    @Test
    void contradictoryEmptySentinelWithQuotaOrMovementOrReceiptIsRejected() {
        UUID patient = UUID.randomUUID();
        var row = new GastoPagadoReadPort.Evidencia(null, null, false, UUID.randomUUID(), null,
                null, false, null, null, null, null, null, null, null, false);
        when(port.consultar(patient)).thenReturn(List.of(row));
        assertIncomplete(patient);
    }

    @Test
    void duplicateEmptySentinelsAreRejected() {
        UUID patient = UUID.randomUUID();
        var row = new GastoPagadoReadPort.Evidencia(null, null, false, null, null,
                null, false, null, null, null, null, null, null, null, false);
        when(port.consultar(patient)).thenReturn(List.of(row, row));
        assertIncomplete(patient);
    }
}
