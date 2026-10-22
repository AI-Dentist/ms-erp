package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.application.usecase.CobrarCuotaEfectivoUseCase;
import com.dentalplatform.erp.financebilling.application.command.CobrarEfectivoCommand;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaRabbitListener;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaPayload;
import com.dentalplatform.erp.financebilling.infrastructure.persistence.ReciboRepositoryAdapter;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WithMockUser(username = "admin", roles = "ADMIN")
class FinanceBillingIntegrationTest extends IntegrationTestBase {
    private static final UUID PATIENT = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID SERVICE = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID CASHIER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String PAY = """
            mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) {
              id numeroRecibo montoTotalBs pdfUrlS3 ciNitCliente razonSocialCliente concepto enviadoWhatsapp fechaEmision
            } }""";
    @Autowired ConsultaFinalizadaRabbitListener listener;
    @Autowired CobrarCuotaEfectivoUseCase cash;
    @MockitoSpyBean ReciboRepositoryAdapter receipts;
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;

    private UUID createOrder() {
        String id = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
                    id montoSubtotalBs montoTotalBs modalidadPago cuotas { id montoCuotaBs estado }
                    paciente { ciNit } items { servicio { codigo } precioUnitarioBs subtotalBs cantidad }
                } }""").variable("input", Map.of("pacienteId", PATIENT.toString(), "items",
                List.of(Map.of("servicioId", SERVICE.toString(), "cantidad", 10))))
                .execute().path("crearOrdenCobroManual.montoSubtotalBs").entity(Double.class).isEqualTo(1000.0)
                .path("crearOrdenCobroManual.items[0].servicio.codigo").entity(String.class).isEqualTo("ODG-CONS")
                .path("crearOrdenCobroManual.paciente.ciNit").entity(String.class).isEqualTo("8945123 SC")
                .path("crearOrdenCobroManual.id").entity(String.class).get();
        return UUID.fromString(id);
    }

    private UUID firstQuota(UUID order) {
        return jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ? ORDER BY numero_cuota LIMIT 1",
                UUID.class, order);
    }

    private Map<String, Object> payInput(UUID quota, UUID cashier) {
        return Map.of("cuotaId", quota.toString(), "usuarioCajeroId", cashier.toString(),
                "ciNitCliente", "8945123 SC", "razonSocialCliente", "Carlos Justiniano");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertUnpaid(UUID order, UUID quota) {
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota))
                .isEqualTo("PENDIENTE");
        assertThat(jdbc.queryForObject("SELECT saldo_pendiente_bs FROM ordenes_cobro WHERE id = ?", BigDecimal.class, order))
                .isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ?", Integer.class, quota))
                .isZero();
    }

    @Test
    void seededCashPaymentIsAtomicAndDoublePaymentIsBadRequestWithoutSideEffects() {
        UUID order = UUID.fromString("80000000-0000-0000-0000-000000000001");
        UUID quota = firstQuota(order);
        when(pdf.generar(any())).thenReturn(new byte[]{1, 2});
        when(storage.guardar(any(), any())).thenReturn("file:///receipts/test.pdf");
        String receiptId = graphQl.document(PAY).variable("input", payInput(quota, CASHIER)).execute()
                .path("cobrarCuotaEnEfectivo.numeroRecibo").entity(String.class).matches(s -> s.matches("REC-\\d{4}-\\d{6,}"))
                .path("cobrarCuotaEnEfectivo.montoTotalBs").entity(Double.class).isEqualTo(1400.0)
                .path("cobrarCuotaEnEfectivo.pdfUrlS3").valueIsNull()
                .path("cobrarCuotaEnEfectivo.id").entity(String.class).get();
        assertThat(jdbc.queryForObject("SELECT pdf_url_s3 FROM recibos WHERE id = ?", String.class, UUID.fromString(receiptId)))
                .isEqualTo("file:///receipts/test.pdf");
        graphQl.document("{ obtenerOrdenCobroDetalle(id: \"" + order + "\") { saldoPendienteBs estado cuotas { estado } } }")
                .execute().path("obtenerOrdenCobroDetalle.saldoPendienteBs").entity(Double.class).isEqualTo(2800.0)
                .path("obtenerOrdenCobroDetalle.estado").entity(String.class).isEqualTo("PARCIAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ? AND tipo_movimiento = 'INGRESO' AND metodo_pago = 'EFECTIVO' AND categoria = 'COBRO_CUOTA'", Integer.class, quota)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos WHERE id = ?", Integer.class, UUID.fromString(receiptId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'CUOTA_COBRADA' AND entidad_id = ?", Integer.class, quota)).isEqualTo(1);
        int movements = count("movimientos_caja"), receiptsBefore = count("recibos"), audits = count("audit_logs");
        graphQl.document(PAY).variable("input", payInput(quota, CASHIER)).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        assertThat(count("movimientos_caja")).isEqualTo(movements);
        assertThat(count("recibos")).isEqualTo(receiptsBefore);
        assertThat(count("audit_logs")).isEqualTo(audits);
        assertThat(jdbc.queryForObject("SELECT saldo_pendiente_bs FROM ordenes_cobro WHERE id = ?", BigDecimal.class, order))
                .isEqualByComparingTo("2800");
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota))
                .isEqualTo("PAGADA");
    }

    @Test
    void receiptFailureAfterMovementInsertRollsBackAllFinancialChanges() {
        UUID order = createOrder(), quota = firstQuota(order);
        int receiptCount = count("recibos"), auditCount = count("audit_logs");
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ?", Integer.class, quota))
                    .isEqualTo(1);
            throw new BusinessRuleException("Fallo de recibo inyectado");
        }).when(receipts).guardar(any(Recibo.class));
        graphQl.document(PAY).variable("input", payInput(quota, CASHIER)).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST
                        && e.getMessage().equals("Fallo de recibo inyectado")).verify();
        assertUnpaid(order, quota);
        assertThat(count("recibos")).isEqualTo(receiptCount);
        assertThat(count("audit_logs")).isEqualTo(auditCount);
        verifyNoInteractions(pdf, storage);
    }

    @Test
    void pdfFailureDoesNotRollBackPaymentOrReceipt() {
        UUID order = createOrder(), quota = firstQuota(order);
        when(pdf.generar(any())).thenThrow(new IllegalStateException("PDF unavailable"));
        graphQl.document(PAY).variable("input", payInput(quota, CASHIER)).execute()
                .path("cobrarCuotaEnEfectivo.pdfUrlS3").valueIsNull();
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota)).isEqualTo("PAGADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = ?", Integer.class, quota)).isEqualTo(1);
    }

    @Test
    void storageFailureAlsoLeavesFinancialCommitIntact() {
        UUID order = createOrder(), quota = firstQuota(order);
        when(pdf.generar(any())).thenReturn(new byte[]{1});
        when(storage.guardar(any(), any())).thenThrow(new IllegalStateException("Storage unavailable"));
        graphQl.document(PAY).variable("input", payInput(quota, CASHIER)).execute()
                .path("cobrarCuotaEnEfectivo.pdfUrlS3").valueIsNull();
        assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota)).isEqualTo("PAGADA");
        assertThat(jdbc.queryForObject("SELECT saldo_pendiente_bs FROM ordenes_cobro WHERE id = ?", BigDecimal.class, order))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = ? AND r.pdf_url_s3 IS NULL", Integer.class, quota)).isEqualTo(1);
    }

    @Test
    void unknownCashierIsBadRequestWithoutMutation() {
        UUID order = createOrder(), quota = firstQuota(order);
        int movements = count("movimientos_caja"), receiptCount = count("recibos"), audits = count("audit_logs");
        graphQl.document(PAY).variable("input", payInput(quota, UUID.randomUUID())).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        assertUnpaid(order, quota);
        assertThat(count("movimientos_caja")).isEqualTo(movements);
        assertThat(count("recibos")).isEqualTo(receiptCount);
        assertThat(count("audit_logs")).isEqualTo(audits);
    }

    @Test
    void creditPlanReplacesOldInstallmentAndQueriesReturnIt() {
        UUID order = createOrder();
        graphQl.document("""
                mutation($input: ConfigurarPlanPagosInput!) { configurarPlanDePagos(input: $input) {
                  modalidadPago montoTotalBs saldoPendienteBs descuentoBs cantidadCuotas cuotas { montoCuotaBs fechaVencimiento }
                } }""").variable("input", Map.of("ordenCobroId", order.toString(), "modalidadPago", "CREDITO",
                "descuentoBs", 100, "cantidadCuotas", 3, "fechaPrimeraCuota", "2026-11-01", "intervaloDias", 30))
                .execute().path("configurarPlanDePagos.montoTotalBs").entity(Double.class).isEqualTo(900.0)
                .path("configurarPlanDePagos.cuotas[*].montoCuotaBs").entityList(Double.class)
                .containsExactly(300.0, 300.0, 300.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuotas_pago WHERE orden_cobro_id = ?", Integer.class, order)).isEqualTo(3);
        graphQl.document("{ listarOrdenesCobro(estado: PENDIENTE, pacienteId: \"" + PATIENT + "\") { id } }")
                .execute().path("listarOrdenesCobro[*].id").entityList(String.class).contains(order.toString());
        graphQl.document("{ listarCuotasPendientesPorPaciente(pacienteId: \"" + PATIENT + "\") { id estado } }")
                .execute().path("listarCuotasPendientesPorPaciente[*].id").entityList(String.class).contains(firstQuota(order).toString());
    }

    @Test
    void cashflowTotalsIncludeIncomeExpenseAndAttachedReceipt() {
        String start = OffsetDateTime.now().minusSeconds(1).toString();
        UUID order = createOrder();
        graphQl.document(PAY).variable("input", payInput(firstQuota(order), CASHIER)).execute().path("cobrarCuotaEnEfectivo.id").hasValue();
        graphQl.document("""
                mutation($input: RegistrarEgresoInput!) { registrarEgresoCaja(input: $input) { tipoMovimiento categoria metodoPago montoBs descripcion recibo { id } } }
                """).variable("input", Map.of("usuarioCajeroId", CASHIER.toString(), "categoria", "INSUMOS",
                "metodoPago", "TRANSFERENCIA", "montoBs", 125, "descripcion", "Compra de insumos"))
                .execute().path("registrarEgresoCaja.tipoMovimiento").entity(String.class).isEqualTo("EGRESO")
                .path("registrarEgresoCaja.recibo").valueIsNull();
        var expected = jdbc.queryForMap("SELECT coalesce(sum(monto_bs) FILTER (WHERE tipo_movimiento = 'INGRESO'), 0) ingresos, coalesce(sum(monto_bs) FILTER (WHERE tipo_movimiento = 'EGRESO'), 0) egresos FROM movimientos_caja WHERE fecha_movimiento >= ?", OffsetDateTime.parse(start));
        BigDecimal income = (BigDecimal) expected.get("ingresos"), expense = (BigDecimal) expected.get("egresos");
        graphQl.document("query($start: DateTime!, $end: DateTime!) { obtenerFlujoCaja(fechaInicio: $start, fechaFin: $end) { totalIngresosBs totalEgresosBs saldoNetoBs movimientos { tipoMovimiento recibo { numeroRecibo } } } }")
                .variable("start", start).variable("end", OffsetDateTime.now().plusSeconds(1).toString()).execute()
                .path("obtenerFlujoCaja.totalIngresosBs").entity(Double.class).isEqualTo(income.doubleValue())
                .path("obtenerFlujoCaja.totalEgresosBs").entity(Double.class).isEqualTo(expense.doubleValue())
                .path("obtenerFlujoCaja.saldoNetoBs").entity(Double.class).isEqualTo(income.subtract(expense).doubleValue())
                .path("obtenerFlujoCaja.movimientos[?(@.tipoMovimiento=='INGRESO')].recibo.numeroRecibo")
                .entityList(String.class).satisfies(list -> assertThat(list).isNotEmpty());
    }

    private ConsultaFinalizadaPayload event(UUID consultation, UUID patient, UUID service) {
        return new ConsultaFinalizadaPayload(UUID.randomUUID(), consultation, UUID.randomUUID(), patient,
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                List.of(new ConsultaFinalizadaPayload.ServicioRealizado(service, "36", 2, new BigDecimal("250"))),
                "Aplicar plan de pagos si solicita crédito");
    }

    @Test
    void listenerUsesEventPricesAndIsIdempotentAndRejectsUnknownReferences() {
        UUID consultation = UUID.randomUUID();
        var payload = event(consultation, PATIENT, SERVICE);
        listener.recibir(payload);
        listener.recibir(payload);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordenes_cobro WHERE consulta_externa_id = ?", Integer.class, consultation)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM ordenes_cobro WHERE consulta_externa_id = ?", consultation);
        assertThat(row.get("estado")).isEqualTo("PENDIENTE");
        assertThat(row.get("modalidad_pago")).isEqualTo("CONTADO");
        assertThat((BigDecimal) row.get("monto_total_bs")).isEqualByComparingTo("500");
        assertThat(row.get("observaciones")).isEqualTo(payload.observacionesCobro());
        assertThat(jdbc.queryForObject("SELECT precio_unitario_bs FROM orden_cobro_items WHERE orden_cobro_id = ?", BigDecimal.class, row.get("id"))).isEqualByComparingTo("250");
        int orders = count("ordenes_cobro");
        assertThatThrownBy(() -> listener.recibir(event(UUID.randomUUID(), UUID.randomUUID(), SERVICE))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> listener.recibir(event(UUID.randomUUID(), PATIENT, UUID.randomUUID()))).isInstanceOf(BusinessRuleException.class);
        assertThat(count("ordenes_cobro")).isEqualTo(orders);
    }

    @Test
    void concurrentDuplicateConsultationsCreateOnlyOneOrder() throws Exception {
        UUID consultation = UUID.randomUUID();
        var payload = event(consultation, PATIENT, SERVICE);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                listener.recibir(payload);
                return true;
            })).toList();
            start.countDown();
            for (var task : tasks) { assertThat(task.get()).isTrue(); }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordenes_cobro WHERE consulta_externa_id = ?", Integer.class, consultation)).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ODONTOLOGO")
    void dentistCannotReadFinancialQueries() {
        graphQl.document("{ listarOrdenesCobro { id } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }

    @Test
    void invalidExpenseIsBadRequestAndLeavesNoRows() {
        int movements = count("movimientos_caja"), audits = count("audit_logs");
        graphQl.document("mutation($input: RegistrarEgresoInput!) { registrarEgresoCaja(input: $input) { id } }")
                .variable("input", Map.of("usuarioCajeroId", CASHIER.toString(), "categoria", "OTROS", "metodoPago", "EFECTIVO", "montoBs", 0, "descripcion", "Test"))
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        assertThat(count("movimientos_caja")).isEqualTo(movements);
        assertThat(count("audit_logs")).isEqualTo(audits);
    }

    @Test
    void concurrentCashPaymentsProduceOnlyOneMovementAndReceipt() throws Exception {
        UUID order = createOrder(), quota = firstQuota(order);
        var command = new CobrarEfectivoCommand(quota, CASHIER, "12345", "Cliente concurrente");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { cash.ejecutar(command); return "paid"; }
                catch (BusinessRuleException e) { return "rejected"; }
            })).toList();
            start.countDown();
            assertThat(List.of(tasks.get(0).get(), tasks.get(1).get())).containsExactlyInAnyOrder("paid", "rejected");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ?", Integer.class, quota)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = ?", Integer.class, quota)).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "RECEPCIONISTA")
    void receptionistCanCreateAndQueryButCannotPayOrRegisterExpenses() {
        UUID order = createOrder(), quota = firstQuota(order);
        graphQl.document(PAY).variable("input", payInput(quota, CASHIER)).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        graphQl.document("mutation($input: RegistrarEgresoInput!) { registrarEgresoCaja(input: $input) { id } }")
                .variable("input", Map.of("usuarioCajeroId", CASHIER.toString(), "categoria", "OTROS", "metodoPago", "EFECTIVO", "montoBs", 1, "descripcion", "Test"))
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        assertUnpaid(order, quota);
    }
}
