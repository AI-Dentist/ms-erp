package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

@WithMockUser(roles = "ADMIN")
class GastoPagadoPacienteIntegrationTest extends IntegrationTestBase {
    private static final String QUERY = """
            query($id: UUID!) { obtenerGastoPagadoPaciente(pacienteId: $id) {
              pacienteId moneda completo totalPagadoBs
            } }
            """;
    private static final UUID CASHIER = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private UUID patient(boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes(id,ci_nit,nombres,apellidos,telefono_whatsapp,activo) VALUES (?,?,?,?,?,?)",
                id, id.toString().substring(0, 30), "Synthetic", "Patient", "0", active);
        return id;
    }

    private UUID order(UUID patient, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ordenes_cobro(id,paciente_id,monto_subtotal_bs,monto_total_bs,saldo_pendiente_bs,estado)
                VALUES (?, ?, 100, 100, CASE WHEN ? = 'PENDIENTE' THEN 100 ELSE 0 END, ?)
                """, id, patient, status, status);
        return id;
    }

    private UUID quota(UUID order, int number, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cuotas_pago(id,orden_cobro_id,numero_cuota,monto_cuota_bs,fecha_vencimiento,estado,fecha_pago)
                VALUES (?, ?, ?, 100, CURRENT_DATE, ?, CASE WHEN ? = 'PAGADA' THEN TIMESTAMPTZ '2026-01-01 12:00:00+00' ELSE NULL END)
                """, id, order, number, status, status);
        if (number == 2) jdbc.update("UPDATE ordenes_cobro SET cantidad_cuotas=2, monto_subtotal_bs=200, monto_total_bs=200, saldo_pendiente_bs=CASE WHEN estado='PARCIAL' THEN 100 ELSE 0 END WHERE id=?", order);
        return id;
    }

    private UUID movement(UUID quota, String method) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO movimientos_caja(id,cuota_id,usuario_cajero_id,tipo_movimiento,categoria,metodo_pago,monto_bs,descripcion,fecha_movimiento)
                VALUES (?, ?, ?, 'INGRESO', 'COBRO_CUOTA', ?, 100, 'synthetic', TIMESTAMPTZ '2026-01-01 12:00:00+00')
                """, id, quota, CASHIER, method);
        return id;
    }

    private UUID receipt(UUID movement) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recibos(id,movimiento_caja_id,numero_recibo,ci_nit_cliente,razon_social_cliente,concepto,monto_total_bs,fecha_emision)
                VALUES (?, ?, ?, '0', 'Synthetic', 'Synthetic', 100, TIMESTAMPTZ '2026-01-01 12:00:00+00')
                """, id, movement, id.toString());
        return id;
    }

    private void result(UUID patient, boolean complete, Double amount) {
        var response = graphQl.document(QUERY).variable("id", patient.toString()).execute()
                .path("obtenerGastoPagadoPaciente.pacienteId").entity(String.class).isEqualTo(patient.toString())
                .path("obtenerGastoPagadoPaciente.moneda").entity(String.class).isEqualTo("BOB")
                .path("obtenerGastoPagadoPaciente.completo").entity(Boolean.class).isEqualTo(complete);
        if (amount == null) response.path("obtenerGastoPagadoPaciente.totalPagadoBs").valueIsNull();
        else response.path("obtenerGastoPagadoPaciente.totalPagadoBs").entity(Double.class).isEqualTo(amount);
    }

    @Test
    void emptyUnpaidAndInactiveHistoriesAreZeroAndIsolated() {
        UUID empty = patient(false), other = patient(true);
        UUID paid = quota(order(other, "PAGADO"), 1, "PAGADA");
        receipt(movement(paid, "EFECTIVO"));
        result(empty, true, 0.0);
        quota(order(empty, "PENDIENTE"), 1, "PENDIENTE");
        result(empty, true, 0.0);
        result(other, true, 100.0);
    }

    @Test
    void partialOrderCountsOnlyPaidCashAndDoesNotChangeExistingQuery() {
        UUID patient = patient(true), order = order(patient, "PARCIAL");
        UUID paid = quota(order, 1, "PAGADA");
        quota(order, 2, "PENDIENTE");
        receipt(movement(paid, "EFECTIVO"));
        String oldQuery = "query($id: UUID!) { listarOrdenesCobro(pacienteId: $id) { id estado cuotas { estado } } }";
        var before = graphQl.document(oldQuery).variable("id", patient.toString()).execute()
                .path("listarOrdenesCobro[0].estado").entity(String.class).get();
        result(patient, true, 100.0);
        graphQl.document(oldQuery).variable("id", patient.toString()).execute()
                .path("listarOrdenesCobro[0].estado").entity(String.class).isEqualTo(before)
                .path("listarOrdenesCobro[0].cuotas[*].estado").entityList(String.class)
                .satisfies(states -> org.assertj.core.api.Assertions.assertThat(states).containsExactlyInAnyOrder("PAGADA", "PENDIENTE"));
    }

    @Test
    void negativeUnpaidQuotaInDatabaseReturnsIncomplete() {
        UUID patient = patient(true), order = order(patient, "PENDIENTE");
        UUID unpaid = quota(order, 1, "PENDIENTE");
        jdbc.update("UPDATE cuotas_pago SET monto_cuota_bs = -100 WHERE id = ?", unpaid);
        jdbc.update("UPDATE ordenes_cobro SET monto_subtotal_bs = -100, monto_total_bs = -100, saldo_pendiente_bs = -100 WHERE id = ?", order);
        result(patient, false, null);
    }

    @Test
    void queryProducesNoObservableSideEffectsOnOrdersCuotasCashflowOrReceipts() {
        UUID patient = patient(true), order = order(patient, "PARCIAL");
        UUID paid = quota(order, 1, "PAGADA");
        quota(order, 2, "PENDIENTE");
        receipt(movement(paid, "EFECTIVO"));

        UUID corruptPatient = patient(true), corruptOrder = order(corruptPatient, "PAGADO");
        receipt(movement(quota(corruptOrder, 1, "PAGADA"), "EFECTIVO"));
        jdbc.update("UPDATE ordenes_cobro SET saldo_pendiente_bs = 99 WHERE id = ?", corruptOrder);

        String ordersQuery = """
                query($id: UUID!) {
                  listarOrdenesCobro(pacienteId: $id) {
                    id montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs cantidadCuotas estado
                    cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado fechaPago }
                  }
                }
                """;
        String cashflowQuery = """
                query($from: DateTime!, $to: DateTime!) {
                  obtenerFlujoCaja(fechaInicio: $from, fechaFin: $to) {
                    totalIngresosBs totalEgresosBs saldoNetoBs
                    movimientos {
                      id tipoMovimiento categoria metodoPago montoBs descripcion fechaMovimiento
                      recibo { id numeroRecibo ciNitCliente razonSocialCliente concepto montoTotalBs fechaEmision }
                    }
                  }
                }
                """;

        var ordersBefore = graphQl.document(ordersQuery).variable("id", patient.toString()).execute()
                .path("listarOrdenesCobro").entity(Object.class).get();
        var corruptOrdersBefore = graphQl.document(ordersQuery).variable("id", corruptPatient.toString()).execute()
                .path("listarOrdenesCobro").entity(Object.class).get();
        var cashflowBefore = graphQl.document(cashflowQuery)
                .variable("from", "2026-01-01T00:00:00Z").variable("to", "2026-01-02T00:00:00Z").execute()
                .path("obtenerFlujoCaja").entity(Object.class).get();

        result(patient, true, 100.0);

        var ordersAfterSuccess = graphQl.document(ordersQuery).variable("id", patient.toString()).execute()
                .path("listarOrdenesCobro").entity(Object.class).get();
        var cashflowAfterSuccess = graphQl.document(cashflowQuery)
                .variable("from", "2026-01-01T00:00:00Z").variable("to", "2026-01-02T00:00:00Z").execute()
                .path("obtenerFlujoCaja").entity(Object.class).get();

        org.assertj.core.api.Assertions.assertThat(ordersAfterSuccess).isEqualTo(ordersBefore);
        org.assertj.core.api.Assertions.assertThat(cashflowAfterSuccess).isEqualTo(cashflowBefore);

        result(corruptPatient, false, null);

        var corruptOrdersAfterIncomplete = graphQl.document(ordersQuery).variable("id", corruptPatient.toString()).execute()
                .path("listarOrdenesCobro").entity(Object.class).get();
        var cashflowAfterIncomplete = graphQl.document(cashflowQuery)
                .variable("from", "2026-01-01T00:00:00Z").variable("to", "2026-01-02T00:00:00Z").execute()
                .path("obtenerFlujoCaja").entity(Object.class).get();

        org.assertj.core.api.Assertions.assertThat(corruptOrdersAfterIncomplete).isEqualTo(corruptOrdersBefore);
        org.assertj.core.api.Assertions.assertThat(cashflowAfterIncomplete).isEqualTo(cashflowBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingMovement", "missingReceipt", "amount", "status", "date", "method", "category", "type", "unpaidMovement", "unknownOrder", "missingQuota", "balance"})
    void inconsistentEvidenceNeverReturnsAnActualTotal(String defect) {
        UUID patient = patient(true), order = order(patient, "PAGADO");
        UUID paid = quota(order, 1, "PAGADA");
        if (!defect.equals("missingMovement")) {
            UUID movement = movement(paid, defect.equals("method") ? "TRANSFERENCIA" : "EFECTIVO");
            if (!defect.equals("missingReceipt")) {
                UUID receipt = receipt(movement);
                if (defect.equals("amount")) jdbc.update("UPDATE recibos SET monto_total_bs=99 WHERE id=?", receipt);
            }
            if (defect.equals("date")) jdbc.update("UPDATE movimientos_caja SET fecha_movimiento=fecha_movimiento + interval '1 day' WHERE id=?", movement);
            if (defect.equals("category")) jdbc.update("UPDATE movimientos_caja SET categoria='OTROS' WHERE id=?", movement);
            if (defect.equals("type")) jdbc.update("UPDATE movimientos_caja SET tipo_movimiento='EGRESO' WHERE id=?", movement);
        }
        if (defect.equals("status")) jdbc.update("UPDATE cuotas_pago SET estado='UNKNOWN' WHERE id=?", paid);
        if (defect.equals("unpaidMovement")) jdbc.update("UPDATE cuotas_pago SET estado='PENDIENTE', fecha_pago=NULL WHERE id=?", paid);
        if (defect.equals("unknownOrder")) jdbc.update("UPDATE ordenes_cobro SET estado='UNKNOWN' WHERE id=?", order);
        if (defect.equals("missingQuota")) jdbc.update("UPDATE ordenes_cobro SET cantidad_cuotas=2 WHERE id=?", order);
        if (defect.equals("balance")) jdbc.update("UPDATE ordenes_cobro SET saldo_pendiente_bs=50 WHERE id=?", order);
        result(patient, false, null);
    }

    @Test
    void qrMixedWithCashIsNotTheNominalTotal() {
        UUID patient = patient(true), order = order(patient, "PAGADO");
        receipt(movement(quota(order, 1, "PAGADA"), "EFECTIVO"));
        receipt(movement(quota(order, 2, "PAGADA"), "QR_PAGOFACIL"));
        result(patient, false, null);
    }

    @Test
    void missingPatientIsAnError() {
        graphQl.document(QUERY).variable("id", UUID.randomUUID().toString()).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.NOT_FOUND).verify();
    }

    @Test @WithMockUser(roles = "CAJERO")
    void cashierCanRead() { result(patient(true), true, 0.0); }

    @Test @WithMockUser(roles = "RECEPCIONISTA")
    void receptionistCanRead() { result(patient(true), true, 0.0); }

    @Test @WithMockUser(roles = "ODONTOLOGO")
    void dentistCannotReadEitherFinancialQuery() {
        graphQl.document(QUERY).variable("id", UUID.randomUUID().toString()).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        graphQl.document("{ listarOrdenesCobro { id } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }
}
