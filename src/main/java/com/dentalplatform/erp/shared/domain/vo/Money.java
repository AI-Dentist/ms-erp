package com.dentalplatform.erp.shared.domain.vo;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-negative amount in Bolivianos (Bs), always kept with two decimals.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final String CURRENCY_CODE = "BOB";
    public static final String SYMBOL = "Bs";
    private static final int SCALE = 2;

    public Money {
        if (amount == null) {
            throw new BusinessRuleException("El monto es obligatorio");
        }
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
        if (amount.signum() < 0) {
            throw new BusinessRuleException("El monto no puede ser negativo: " + amount);
        }
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    /**
     * Splits the amount into equal parts rounded down to the cent; the last part absorbs the remainder
     * so the parts always add up to the original amount.
     */
    public List<Money> splitInto(int parts) {
        if (parts < 1) {
            throw new BusinessRuleException("La cantidad de partes debe ser al menos 1");
        }
        BigDecimal base = amount.divide(BigDecimal.valueOf(parts), SCALE, RoundingMode.DOWN);
        List<Money> result = new ArrayList<>(parts);
        for (int i = 0; i < parts - 1; i++) {
            result.add(new Money(base));
        }
        result.add(new Money(amount.subtract(base.multiply(BigDecimal.valueOf(parts - 1)))));
        return List.copyOf(result);
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return SYMBOL + " " + amount.toPlainString();
    }
}
