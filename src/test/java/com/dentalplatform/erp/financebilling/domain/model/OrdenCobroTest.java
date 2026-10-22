package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class OrdenCobroTest {
    private final OffsetDateTime now = OffsetDateTime.parse("2026-06-01T10:00:00-04:00");

    private OrdenCobro order() {
        return OrdenCobro.crear(UUID.randomUUID(), null, null,
                List.of(OrdenCobroItem.crear(UUID.randomUUID(), "36", 1, Money.of("1000"))), null, now);
    }

    @Test
    void defaultIsPendingCashDueToday() {
        var o = order();
        assertThat(o.getEstado()).isEqualTo(EstadoOrdenCobro.PENDIENTE);
        assertThat(o.getModalidadPago()).isEqualTo(ModalidadPago.CONTADO);
        assertThat(o.getCuotas()).hasSize(1);
        assertThat(o.getCuotas().getFirst().fechaVencimiento()).isEqualTo(now.toLocalDate());
        assertThat(o.getSaldoPendiente()).isEqualTo(Money.of("1000"));
    }

    @Test
    void splitRemainderIsOnLastInstallment() {
        var o = order();
        o.configurarPlanDePagos(ModalidadPago.CREDITO, Money.zero(), 3, LocalDate.of(2026, 7, 1), 30);
        assertThat(o.getCuotas()).extracting(CuotaPago::montoCuota)
                .containsExactly(Money.of("333.33"), Money.of("333.33"), Money.of("333.34"));
        assertThat(o.getCuotas()).extracting(CuotaPago::fechaVencimiento)
                .containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 30));
    }

    @Test
    void excessiveDiscountIsRejectedWithoutChangingPlan() {
        var o = order();
        var before = o.getCuotas();
        assertThatThrownBy(() -> o.configurarPlanDePagos(ModalidadPago.CREDITO, Money.of("1000.01"), 3,
                now.toLocalDate(), 30)).isInstanceOf(BusinessRuleException.class);
        assertThat(o.getCuotas()).isEqualTo(before);
        assertThat(o.getTotal()).isEqualTo(Money.of("1000"));
    }

    @Test
    void invalidPlanBoundariesAreRejectedWithoutChanges() {
        var o = order();
        var before = o.getCuotas();
        for (int count : new int[]{0, 2}) {
            assertThatThrownBy(() -> o.configurarPlanDePagos(ModalidadPago.CONTADO, Money.zero(), count,
                    now.toLocalDate(), 30)).isInstanceOf(BusinessRuleException.class);
        }
        for (int count : new int[]{1, 37}) {
            assertThatThrownBy(() -> o.configurarPlanDePagos(ModalidadPago.CREDITO, Money.zero(), count,
                    now.toLocalDate(), 30)).isInstanceOf(BusinessRuleException.class);
        }
        assertThatThrownBy(() -> o.configurarPlanDePagos(ModalidadPago.CREDITO, Money.zero(), 3,
                now.toLocalDate(), 0)).isInstanceOf(BusinessRuleException.class);
        assertThat(o.getCuotas()).isEqualTo(before);
        o.configurarPlanDePagos(ModalidadPago.CREDITO, Money.of("100"), 36, now.toLocalDate(), 1);
        assertThat(o.getCuotas()).hasSize(36);
        assertThat(o.getTotal()).isEqualTo(Money.of("900"));
    }

    @Test
    void paymentsTransitionPendingPartialPaidAndCannotBeRepeatedOrReplanned() {
        var o = order();
        o.configurarPlanDePagos(ModalidadPago.CREDITO, Money.zero(), 2, now.toLocalDate(), 30);
        UUID first = o.getCuotas().getFirst().id();
        o.registrarPagoCuota(first, now);
        assertThat(o.getEstado()).isEqualTo(EstadoOrdenCobro.PARCIAL);
        assertThat(o.getSaldoPendiente()).isEqualTo(Money.of("500"));
        assertThat(o.getCuotas().getFirst().estado()).isEqualTo(EstadoCuota.PAGADA);
        assertThat(o.getCuotas().getFirst().fechaPago()).isEqualTo(now);
        assertThatThrownBy(() -> o.registrarPagoCuota(first, now)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> o.configurarPlanDePagos(ModalidadPago.CONTADO, Money.zero(), 1,
                now.toLocalDate(), 30)).isInstanceOf(BusinessRuleException.class);
        assertThat(o.getSaldoPendiente()).isEqualTo(Money.of("500"));
        o.registrarPagoCuota(o.getCuotas().getLast().id(), now);
        assertThat(o.getEstado()).isEqualTo(EstadoOrdenCobro.PAGADO);
        assertThat(o.getSaldoPendiente()).isEqualTo(Money.zero());
    }

    @Test
    void annulledOrderCannotBePaid() {
        var original = order();
        var annulled = new OrdenCobro(original.getId(), original.getPacienteId(), null, null,
                ModalidadPago.CONTADO, Money.of("1000"), Money.zero(), Money.of("1000"), Money.of("1000"),
                EstadoOrdenCobro.ANULADO, null, now, original.getItems(), original.getCuotas());
        assertThatThrownBy(() -> annulled.registrarPagoCuota(annulled.getCuotas().getFirst().id(), now))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(annulled.getSaldoPendiente()).isEqualTo(Money.of("1000"));
    }
}
