package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Morosidad is evaluated at min(fechaFin, now): with fechaFin in the future the evaluation date is "today",
 * so installments due later in the period are exigibles but not yet vencidas. "Now" is a fixed clock.
 */
@WithMockUser(roles = "ADMIN")
class KpisMorosidadEvaluationDateTest extends IntegrationTestBase {
    private static final String QUERY = """
            query($start: DateTime!, $end: DateTime!) {
              obtenerKpisFinancieros(fechaInicio: $start, fechaFin: $end) {
                morosidad { cuotasVencidas cuotasExigibles porcentaje montoVencidoBs detalle { numeroCuota diasAtraso } }
              }
            }
            """;

    @MockitoBean Clock clock;

    @BeforeEach void fixNow() {
        when(clock.instant()).thenReturn(Instant.parse("2031-06-15T16:00:00Z")); // 12:00 in La Paz
        when(clock.getZone()).thenReturn(ZoneId.of("America/La_Paz"));
    }

    @Test
    void futurePeriodEndEvaluatesAtNowAndRoundsPercentToTwoDecimals() {
        UUID patient = UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes(id, ci_nit, nombres, apellidos, telefono_whatsapp) VALUES (?, ?, 'Eval', 'Date', '0')",
                patient, patient.toString().substring(0, 30));
        UUID order = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ordenes_cobro(id, paciente_id, monto_subtotal_bs, monto_total_bs, saldo_pendiente_bs, cantidad_cuotas, created_at)
                VALUES (?, ?, 300, 300, 200, 3, TIMESTAMPTZ '2031-05-01 10:00:00-04')
                """, order, patient);
        quota(order, 1, "2031-06-10", "PENDIENTE"); // before now -> vencida, 5 days late
        quota(order, 2, "2031-06-20", "PENDIENTE"); // after now -> exigible only
        quota(order, 3, "2031-06-25", "PAGADA");    // paid -> exigible only

        graphQl.document(QUERY)
                .variable("start", "2031-06-01T00:00:00-04:00")
                .variable("end", "2031-06-30T23:59:59-04:00")
                .execute()
                .path("obtenerKpisFinancieros.morosidad.cuotasExigibles").entity(Integer.class).isEqualTo(3)
                .path("obtenerKpisFinancieros.morosidad.cuotasVencidas").entity(Integer.class).isEqualTo(1)
                .path("obtenerKpisFinancieros.morosidad.porcentaje").entity(Double.class).isEqualTo(33.33)
                .path("obtenerKpisFinancieros.morosidad.montoVencidoBs").entity(Double.class).isEqualTo(100.0)
                .path("obtenerKpisFinancieros.morosidad.detalle[0].numeroCuota").entity(Integer.class).isEqualTo(1)
                .path("obtenerKpisFinancieros.morosidad.detalle[0].diasAtraso").entity(Integer.class).isEqualTo(5);
    }

    private void quota(UUID order, int number, String due, String status) {
        jdbc.update("""
                INSERT INTO cuotas_pago(id, orden_cobro_id, numero_cuota, monto_cuota_bs, fecha_vencimiento, estado)
                VALUES (?, ?, ?, 100, ?::date, ?)
                """, UUID.randomUUID(), order, number, due, status);
    }
}
