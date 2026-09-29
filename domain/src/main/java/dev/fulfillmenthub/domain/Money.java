package dev.fulfillmenthub.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        if (currency == null || currency.isBlank() || currency.length() != 3) {
            throw new DomainException("Currency must be a 3-letter ISO 4217 code.");
        }
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        currency = currency.toUpperCase(Locale.ROOT);
    }

    public static Money brl(String amount) {
        return new Money(new BigDecimal(amount), "BRL");
    }

    public Money add(Money other) {
        sameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        sameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public long cents() {
        return amount.movePointRight(2).longValueExact();
    }

    private void sameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new DomainException("Cannot combine different currencies.");
        }
    }
}
