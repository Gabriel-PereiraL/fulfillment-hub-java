package dev.fulfillmenthub.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyAndProductTest {
    @ParameterizedTest
    @CsvSource({"1.005,1.00", "1.015,1.02", "-1.005,-1.00", "89.9,89.90"})
    void moneyUsesBankersRounding(String input, String expected) {
        assertEquals(new BigDecimal(expected), Money.brl(input).amount());
    }

    @Test void moneyHasNormalizedValueEquality() {
        assertEquals(new Money(new BigDecimal("10"), "brl"), Money.brl("10.00"));
        assertEquals(Money.brl("20"), Money.brl("10").multiply(2));
        assertEquals(Money.brl("-10"), Money.brl("10").subtract(Money.brl("20")));
        assertEquals(1099L, Money.brl("10.99").cents());
    }

    @Test void cannotMixCurrencies() {
        assertThrows(DomainException.class, () -> Money.brl("1").add(new Money(BigDecimal.ONE, "USD")));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "B", "BR", "BRLL"})
    void rejectsInvalidCurrency(String value) {
        assertThrows(DomainException.class, () -> new Money(BigDecimal.ONE, value));
    }

    @Test void reserveLastUnitAndRelease() {
        var p = new Product(UUID.randomUUID(), " sku ", "Product", Money.brl("12"), 1, Instant.EPOCH);
        assertEquals("SKU", p.sku());
        p.reserve(1, Instant.EPOCH);
        assertEquals(0, p.stockQuantity());
        assertThrows(DomainException.class, () -> p.reserve(1, Instant.EPOCH));
        assertEquals(0, p.stockQuantity());
        p.release(1, Instant.EPOCH);
        assertEquals(1, p.stockQuantity());
        p.setActive(false, Instant.EPOCH);
        assertThrows(DomainException.class, () -> p.reserve(1, Instant.EPOCH));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidReservationNeverMutatesStock(int quantity) {
        var p = new Product(UUID.randomUUID(), "SKU", "Product", Money.brl("1"), 5, Instant.EPOCH);
        assertThrows(DomainException.class, () -> p.reserve(quantity, Instant.EPOCH));
        assertThrows(DomainException.class, () -> p.release(quantity, Instant.EPOCH));
        assertEquals(5, p.stockQuantity());
    }

    @Test void addressNormalizesWithoutEchoingPersonalDataInErrors() {
        var a = new Address(" Rua A ", "1", " ", "District", "City", "PE", "51020-000", "br", null, null);
        assertEquals("51020000", a.postalCode());
        assertEquals("BR", a.country());
        assertNull(a.complement());
        var e = assertThrows(DomainException.class, () -> new Address("personal".repeat(50), "1", null, "D", "C", "PE", "51000000", "BR", null, null));
        assertFalse(e.getMessage().contains("personal"));
    }

    @Test void coordinatesMustBePairedAndFinite() {
        assertThrows(DomainException.class, () -> new Address("A", "1", null, "D", "C", "PE", "51000000", "BR", 1.0, null));
        assertThrows(DomainException.class, () -> new Address("A", "1", null, "D", "C", "PE", "51000000", "BR", Double.NaN, 1.0));
    }

    @Test void phoneNormalizesAndMasks() {
        var phone = new PhoneNumber("+55 (81) 99999-0000");
        assertEquals("+5581999990000", phone.value());
        assertEquals("+55*******0000", phone.masked());
    }
}
