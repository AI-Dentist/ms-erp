package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.command.*;
import com.dentalplatform.erp.financebilling.application.port.*;
import com.dentalplatform.erp.financebilling.application.usecase.*;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = "erp.pagofacil.mock-enabled=true")
@WithMockUser(username = "admin", roles = "ADMIN")
class CopilotFoundationsIntegrationTest extends IntegrationTestBase {
    static final UUID PATIENT = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final UUID CASHIER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID SERVICE = UUID.fromString("70000000-0000-0000-0000-000000000001");
    @Autowired AnularOrdenCobroUseCase annul;
    @Autowired ReprogramarPlanPagosUseCase reschedule;
    @Autowired ConfigurarPlanPagosUseCase configure;
    @Autowired MonitoreoNegocioUseCase monitoring;
    @Autowired CobrarCuotaEfectivoUseCase cash;
    @Autowired GenerarQrPagoFacilUseCase qr;
    @Autowired ConfirmarPagoQrWebhookUseCase callback;
    @Autowired OrdenCobroRepository orders;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean ReceiptPdfGeneratorPort pdf;
    @MockitoBean ReceiptStoragePort storage;

    OrdenCobro order() {
        return new TransactionTemplate(manager).execute(s -> orders.guardar(OrdenCobro.crear(PATIENT, null, null,
                List.of(OrdenCobroItem.crear(SERVICE, null, 1, Money.of("997.01"))), null, OffsetDateTime.now())));
    }
    OrdenCobro read(UUID id) { return orders.buscarPorId(id).orElseThrow(); }
    CobrarEfectivoCommand payment(UUID quota) { return new CobrarEfectivoCommand(quota, CASHIER, "12345", "Cliente sintético"); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    @Test
    void annulmentRetainsRowsAuditReasonAndRejectsQrGenerationAndExistingQrPayment() {
        var o = order();
        var quota = o.getCuotas().getFirst();
        var transaction = qr.ejecutar(quota.id(), "12345", "Cliente sintético");
        int movements = count("movimientos_caja"), receipts = count("recibos"), audits = count("audit_logs");
        annul.ejecutar(o.getId(), "  Error de registro  ");
        var saved = read(o.getId());
        assertThat(saved.getEstado()).isEqualTo(EstadoOrdenCobro.ANULADO);
        assertThat(saved.getSaldoPendiente()).isEqualTo(Money.zero());
        assertThat(saved.getTotal()).isEqualTo(o.getTotal());
        assertThat(saved.getItems()).isEqualTo(o.getItems());
        assertThat(saved.getCuotas()).extracting(CuotaPago::id).containsExactly(quota.id());
        assertThat(saved.getCuotas().getFirst().estado()).isEqualTo(EstadoCuota.ANULADA);
        assertThat(jdbc.queryForObject("SELECT detalle_json ->> 'motivo' FROM audit_logs WHERE accion = 'ORDEN_COBRO_ANULADA' AND entidad_id = ?", String.class, o.getId())).isEqualTo("Error de registro");
        assertThatThrownBy(() -> annul.ejecutar(o.getId(), "Otra vez")).hasMessage("La orden de cobro ya está anulada");
        assertThatThrownBy(() -> cash.ejecutar(payment(quota.id()))).hasMessage("No se puede cobrar una orden anulada");
        assertThatThrownBy(() -> qr.ejecutar(quota.id(), "1", "Cliente")).hasMessage("La cuota no admite cobro QR");
        assertThatThrownBy(() -> callback.ejecutar(transaction.pagofacilTransactionId(), transaction.monto().amount(), OffsetDateTime.now()))
                .hasMessage("No se puede cobrar una orden anulada");
        assertThat(count("movimientos_caja")).isEqualTo(movements);
        assertThat(count("recibos")).isEqualTo(receipts);
        assertThat(count("audit_logs")).isEqualTo(audits + 1);
        assertThat(jdbc.queryForObject("SELECT estado FROM transacciones_qr WHERE id = ?", String.class, transaction.id())).isEqualTo("GENERADO");
        assertThat(read(o.getId()).getCuotas()).isEqualTo(saved.getCuotas());
    }

    @Test
    void paidPlanCannotBeAnnulledOrRescheduledAndRejectionChangesNothing() {
        var o = order();
        configure.ejecutar(new ConfigurarPlanPagosCommand(o.getId(), ModalidadPago.CREDITO, BigDecimal.ZERO, 2, LocalDate.now(), 30));
        cash.ejecutar(payment(read(o.getId()).getCuotas().getFirst().id()));
        var before = read(o.getId());
        int audits = count("audit_logs"), movements = count("movimientos_caja"), receipts = count("recibos");
        assertThatThrownBy(() -> annul.ejecutar(o.getId(), "Error")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, BigDecimal.ZERO, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);
        var after = read(o.getId());
        assertThat(after.getEstado()).isEqualTo(before.getEstado());
        assertThat(after.getSaldoPendiente()).isEqualTo(before.getSaldoPendiente());
        assertThat(after.getCuotas()).isEqualTo(before.getCuotas());
        assertThat(count("audit_logs")).isEqualTo(audits);
        assertThat(count("movimientos_caja")).isEqualTo(movements);
        assertThat(count("recibos")).isEqualTo(receipts);
    }

    @Test
    void partialPlanChangesPreserveOtherAspectsAndCentsAndUseExistingAudit() {
        var o = order();
        var first = LocalDate.of(2033, 4, 1);
        configure.ejecutar(new ConfigurarPlanPagosCommand(o.getId(), ModalidadPago.CREDITO, new BigDecimal("10.01"), 3, first, 17));
        var changed = reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, null, null, first.plusDays(5), null));
        assertThat(changed.getCuotas()).extracting(CuotaPago::fechaVencimiento).containsExactly(first.plusDays(5), first.plusDays(22), first.plusDays(39));
        assertThat(changed.getDescuento()).isEqualTo(Money.of("10.01"));
        var noDiscount = reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, BigDecimal.ZERO, null, null, null));
        assertThat(noDiscount.getCuotas()).extracting(CuotaPago::montoCuota).containsExactly(Money.of("332.33"), Money.of("332.33"), Money.of("332.35"));
        var countChange = reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, null, 2, null, 11));
        assertThat(countChange.getCuotas()).extracting(CuotaPago::fechaVencimiento).containsExactly(first.plusDays(5), first.plusDays(16));
        assertThat(countChange.getTotal()).isEqualTo(Money.of("997.01"));
        assertThat(countChange.getCuotas()).extracting(CuotaPago::montoCuota).containsExactly(Money.of("498.50"), Money.of("498.51"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'PLAN_PAGOS_CONFIGURADO' AND entidad_id = ?", Integer.class, o.getId())).isEqualTo(4);
    }

    @Test
    void concurrentAnnulAndPaymentHaveExactlyOneWinnerAndConsistentLedger() throws Exception {
        var o = order();
        var quota = o.getCuotas().getFirst().id();
        var context = SecurityContextHolder.getContext();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                SecurityContextHolder.setContext(context);
                try {
                    ready.countDown(); start.await();
                    try { if (i == 0) annul.ejecutar(o.getId(), "Duplicada"); else cash.ejecutar(payment(quota)); return true; }
                    catch (BusinessRuleException e) { return false; }
                } finally { SecurityContextHolder.clearContext(); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var saved = read(o.getId());
        boolean paid = saved.getEstado() == EstadoOrdenCobro.PAGADO;
        assertThat(saved.getEstado()).isIn(EstadoOrdenCobro.PAGADO, EstadoOrdenCobro.ANULADO);
        assertThat(saved.getSaldoPendiente()).isEqualTo(Money.zero());
        assertThat(saved.getCuotas().getFirst().estado()).isEqualTo(paid ? EstadoCuota.PAGADA : EstadoCuota.ANULADA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id = ?", Integer.class, quota)).isEqualTo(paid ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recibos r JOIN movimientos_caja m ON m.id = r.movimiento_caja_id WHERE m.cuota_id = ?", Integer.class, quota)).isEqualTo(paid ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE (accion = 'ORDEN_COBRO_ANULADA' AND entidad_id = ?) OR (accion = 'CUOTA_COBRADA' AND entidad_id = ?)", Integer.class, o.getId(), quota)).isEqualTo(1);
    }

    @Test
    void monitoringUsesInclusiveCashRangeCurrentDebtAndStrictPendingOverdueDateWithoutMutation() {
        var from = OffsetDateTime.parse("1991-01-01T00:00:00-04:00");
        var to = from.plusDays(1);
        var before = monitoring.resumenFinanciero(from, to);
        var o = order();
        configure.ejecutar(new ConfigurarPlanPagosCommand(o.getId(), ModalidadPago.CREDITO, BigDecimal.ZERO, 3, LocalDate.of(1890, 1, 1), 10));
        for (var values : List.of(new String[]{"INGRESO", "EFECTIVO", "23.17"}, new String[]{"EGRESO", "EFECTIVO", "3.11"}, new String[]{"INGRESO", "QR_PAGOFACIL", "41.29"})) {
            jdbc.update("INSERT INTO movimientos_caja(id, usuario_cajero_id, tipo_movimiento, categoria, metodo_pago, monto_bs, descripcion, fecha_movimiento) VALUES (?, ?, ?, 'TEST', ?, ?, 'Sintético', ?)",
                    UUID.randomUUID(), CASHIER, values[0], values[1], new BigDecimal(values[2]), from);
        }
        jdbc.update("INSERT INTO movimientos_caja(id, usuario_cajero_id, tipo_movimiento, categoria, metodo_pago, monto_bs, descripcion, fecha_movimiento) VALUES (?, ?, 'EGRESO', 'TEST', 'QR_PAGOFACIL', 1.07, 'Sintético', ?)", UUID.randomUUID(), CASHIER, to);
        jdbc.update("INSERT INTO movimientos_caja(id, usuario_cajero_id, tipo_movimiento, categoria, metodo_pago, monto_bs, descripcion, fecha_movimiento) VALUES (?, ?, 'INGRESO', 'TEST', 'EFECTIVO', 999.99, 'Fuera del rango', ?)", UUID.randomUUID(), CASHIER, to.plusSeconds(1));
        var summary = monitoring.resumenFinanciero(from, to);
        assertThat(summary.ordenesPendientes()).isEqualTo(before.ordenesPendientes() + 1);
        assertThat(summary.ordenesParciales()).isEqualTo(before.ordenesParciales());
        assertThat(summary.saldoPendienteTotalBs()).isEqualByComparingTo(before.saldoPendienteTotalBs().add(new BigDecimal("997.01")));
        assertThat(summary.porMetodoPago()).filteredOn(r -> r.metodoPago() == MetodoPago.EFECTIVO).singleElement().satisfies(r -> {
            assertThat(r.ingresosBs()).isEqualByComparingTo("23.17"); assertThat(r.egresosBs()).isEqualByComparingTo("3.11"); assertThat(r.saldoNetoBs()).isEqualByComparingTo("20.06");
        });
        assertThat(summary.porMetodoPago()).filteredOn(r -> r.metodoPago() == MetodoPago.QR_PAGOFACIL).singleElement().satisfies(r -> {
            assertThat(r.ingresosBs()).isEqualByComparingTo("41.29"); assertThat(r.egresosBs()).isEqualByComparingTo("1.07"); assertThat(r.saldoNetoBs()).isEqualByComparingTo("40.22");
        });
        int audits = count("audit_logs");
        var cutoff = LocalDate.of(1890, 1, 11);
        var overdue = monitoring.cuotasVencidas(cutoff);
        assertThat(overdue).filteredOn(r -> r.ordenId().equals(o.getId())).singleElement().satisfies(r -> {
            assertThat(r.pacienteId()).isEqualTo(PATIENT); assertThat(r.nombreCompleto()).isNotBlank();
            assertThat(r.ciNit()).isNotBlank(); assertThat(r.telefonoWhatsapp()).isNotBlank();
            assertThat(r.cuotaId()).isEqualTo(read(o.getId()).getCuotas().getFirst().id());
            assertThat(r.numeroCuota()).isEqualTo(1); assertThat(r.montoBs()).isEqualByComparingTo("332.33");
            assertThat(r.vencimiento()).isEqualTo(cutoff.minusDays(10)); assertThat(r.diasAtraso()).isEqualTo(10);
        });
        assertThat(read(o.getId()).getCuotas()).extracting(CuotaPago::estado).containsOnly(EstadoCuota.PENDIENTE);
        assertThat(count("audit_logs")).isEqualTo(audits);
        assertThatThrownBy(() -> monitoring.resumenFinanciero(to, from)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> monitoring.cuotasVencidas(null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void invalidReasonAndFullyPaidOrderLeaveLedgerAndAuditUnchanged() {
        var o = order();
        int audits = count("audit_logs");
        assertThatThrownBy(() -> annul.ejecutar(o.getId(), " "))
                .hasMessage("El motivo de anulación debe tener entre 1 y 500 caracteres");
        assertThat(read(o.getId()).getEstado()).isEqualTo(EstadoOrdenCobro.PENDIENTE);
        assertThat(read(o.getId()).getCuotas()).isEqualTo(o.getCuotas());
        assertThat(count("audit_logs")).isEqualTo(audits);
        cash.ejecutar(payment(o.getCuotas().getFirst().id()));
        var paid = read(o.getId());
        audits = count("audit_logs");
        int movements = count("movimientos_caja"), receipts = count("recibos");
        assertThatThrownBy(() -> annul.ejecutar(o.getId(), "Error"))
                .hasMessage("No se puede anular una orden con pagos registrados");
        assertThat(read(o.getId()).getEstado()).isEqualTo(EstadoOrdenCobro.PAGADO);
        assertThat(read(o.getId()).getCuotas()).isEqualTo(paid.getCuotas());
        assertThat(read(o.getId()).getSaldoPendiente()).isEqualTo(Money.zero());
        assertThat(count("audit_logs")).isEqualTo(audits);
        assertThat(count("movimientos_caja")).isEqualTo(movements);
        assertThat(count("recibos")).isEqualTo(receipts);
    }

    @Test
    void partialPlanRejectsInvalidChangesWithoutChangingStoredTotalsOrRows() {
        var o = order();
        int audits = count("audit_logs");
        for (var c : List.of(
                new ReprogramarPlanPagosCommand(o.getId(), null, null, null, null, null),
                new ReprogramarPlanPagosCommand(o.getId(), null, null, 37, null, null),
                new ReprogramarPlanPagosCommand(o.getId(), null, null, null, null, 0),
                new ReprogramarPlanPagosCommand(o.getId(), null, new BigDecimal("997.02"), null, null, null))) {
            assertThatThrownBy(() -> reschedule.ejecutar(c)).isInstanceOf(BusinessRuleException.class);
            var saved = read(o.getId());
            assertThat(saved.getCuotas()).isEqualTo(o.getCuotas());
            assertThat(saved.getTotal()).isEqualTo(o.getTotal());
            assertThat(saved.getSaldoPendiente()).isEqualTo(o.getSaldoPendiente());
            assertThat(count("audit_logs")).isEqualTo(audits);
        }
        var credit = reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, null, 3, null, null));
        assertThat(credit.getModalidadPago()).isEqualTo(ModalidadPago.CREDITO);
        assertThat(credit.getCuotas().get(1).fechaVencimiento()).isEqualTo(o.getCuotas().getFirst().fechaVencimiento().plusDays(1));
        var contado = reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, null, 1, null, null));
        assertThat(contado.getModalidadPago()).isEqualTo(ModalidadPago.CONTADO);
        assertThat(contado.getCuotas()).extracting(CuotaPago::montoCuota).containsExactly(o.getTotal());
    }

    @Test
    void overdueProjectionExcludesPaidAnnulledOverdueStateAndDueTodayInstallments() {
        var first = LocalDate.of(1880, 1, 1);
        var o = order();
        configure.ejecutar(new ConfigurarPlanPagosCommand(o.getId(), ModalidadPago.CREDITO, BigDecimal.ZERO, 3, first, 10));
        var plan = read(o.getId());
        cash.ejecutar(payment(plan.getCuotas().getFirst().id()));
        jdbc.update("UPDATE cuotas_pago SET estado = 'VENCIDA' WHERE id = ?", plan.getCuotas().get(1).id());
        assertThat(monitoring.cuotasVencidas(first.plusDays(20))).noneMatch(r -> r.ordenId().equals(o.getId()));
        assertThat(monitoring.cuotasVencidas(first.plusDays(21))).filteredOn(r -> r.ordenId().equals(o.getId()))
                .extracting(MonitoreoNegocioPort.CuotaVencida::cuotaId).containsExactly(plan.getCuotas().getLast().id());
        var voided = order();
        configure.ejecutar(new ConfigurarPlanPagosCommand(voided.getId(), ModalidadPago.CONTADO, BigDecimal.ZERO, 1, first, 1));
        annul.ejecutar(voided.getId(), "Duplicada");
        assertThat(monitoring.cuotasVencidas(first.plusDays(30))).noneMatch(r -> r.ordenId().equals(voided.getId()));
    }

    @Test
    @WithMockUser(username = "admin", roles = "CAJERO")
    void cashierCanMonitorRescheduleAndAnnul() {
        var o = order();
        assertThat(reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, BigDecimal.ZERO, null, null, null)).getTotal()).isEqualTo(o.getTotal());
        assertThat(monitoring.cuotasVencidas(LocalDate.of(1700, 1, 1))).isEmpty();
        assertThat(annul.ejecutar(o.getId(), "Duplicada").getEstado()).isEqualTo(EstadoOrdenCobro.ANULADO);
    }

    @Test
    @WithMockUser(username = "admin", roles = "RECEPCIONISTA")
    void receptionistRetainsPlanRoleButCannotAnnulOrReadMonitoringPii() {
        var o = order();
        assertThat(reschedule.ejecutar(new ReprogramarPlanPagosCommand(o.getId(), null, BigDecimal.ZERO, null, null, null)).getTotal()).isEqualTo(o.getTotal());
        assertThatThrownBy(() -> annul.ejecutar(o.getId(), "Error")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> monitoring.cuotasVencidas(LocalDate.now())).isInstanceOf(AccessDeniedException.class);
        assertThat(read(o.getId()).getEstado()).isEqualTo(EstadoOrdenCobro.PENDIENTE);
    }

    @Test
    @WithMockUser(roles = "ODONTOLOGO")
    void dentistCannotAccessNewFinancialOperations() {
        assertThatThrownBy(() -> monitoring.cuotasVencidas(LocalDate.now())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> monitoring.resumenFinanciero(OffsetDateTime.now(), OffsetDateTime.now())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> annul.ejecutar(UUID.randomUUID(), "Error")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reschedule.ejecutar(new ReprogramarPlanPagosCommand(UUID.randomUUID(), null, BigDecimal.ZERO, null, null, null))).isInstanceOf(AccessDeniedException.class);
    }
}
