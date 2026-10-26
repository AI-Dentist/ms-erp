package com.dentalplatform.erp.shared.domain.vo;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesToTwoDecimalsInBolivianos() {
        Money money = Money.of("250.005");

        assertThat(money.amount()).isEqualByComparingTo("250.01");
        assertThat(money.amount().scale()).isEqualTo(2);
        assertThat(money.toString()).isEqualTo("Bs 250.01");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.of("-0.01"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> Money.of((BigDecimal) null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void addsSubtractsAndMultiplies() {
        Money a = Money.of("1400.00");

        assertThat(a.plus(Money.of("100.50"))).isEqualTo(Money.of("1500.50"));
        assertThat(a.minus(Money.of("400"))).isEqualTo(Money.of("1000"));
        assertThat(a.times(3)).isEqualTo(Money.of("4200"));
    }

    @Test
    void subtractionCannotGoBelowZero() {
        assertThatThrownBy(() -> Money.of("100").minus(Money.of("100.01")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void equalityIgnoresTrailingZeros() {
        assertThat(Money.of(new BigDecimal("10"))).isEqualTo(Money.of(new BigDecimal("10.00")));
        assertThat(Money.of("10").hashCode()).isEqualTo(Money.of("10.000").hashCode());
    }

    @Test
    void comparesAmounts() {
        assertThat(Money.of("10").isGreaterThan(Money.of("9.99"))).isTrue();
        assertThat(Money.zero().isZero()).isTrue();
        assertThat(Money.of("0.01").isZero()).isFalse();
    }

    @Test
    void splitsIntoInstallmentsPuttingTheRemainderCentsOnTheLastOne() {
        List<Money> parts = Money.of("1000.00").splitInto(3);

        assertThat(parts).containsExactly(Money.of("333.33"), Money.of("333.33"), Money.of("333.34"));
        assertThat(parts.stream().reduce(Money.zero(), Money::plus)).isEqualTo(Money.of("1000.00"));
    }

    @Test
    void splitIntoOneReturnsTheWholeAmount() {
        assertThat(Money.of("450").splitInto(1)).containsExactly(Money.of("450"));
    }

    @Test
    void splitRequiresAtLeastOnePart() {
        assertThatThrownBy(() -> Money.of("450").splitInto(0)).isInstanceOf(BusinessRuleException.class);
    }
}
