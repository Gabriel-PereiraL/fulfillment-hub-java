package dev.fulfillmenthub.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentAndDeliveryTest {
    private static final Instant NOW = OrderTest.NOW;
    private static Payment payment() { return new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.brl("12"), "simulated-psp", NOW); }
    private static Delivery.Quote quote() { return new Delivery.Quote(UUID.randomUUID(), UUID.randomUUID(), "sim", "quote-1", Money.brl("12"), NOW.plusSeconds(1800), 30, 10, NOW.plusSeconds(900), NOW); }
    private static Delivery delivery() {
        var d = new Delivery(UUID.randomUUID(), quote(), 1, NOW);
        d.confirm("del-1", "https://example.invalid/tracking", Money.brl("12"), NOW);
        return d;
    }

    @Test void paymentAttemptsAreSequentialAndSinglePending() {
        var p = payment();
        var first = p.startAttempt(UUID.randomUUID(), NOW);
        assertThrows(DomainException.class, () -> p.startAttempt(UUID.randomUUID(), NOW));
        p.completeAttempt(first.id(), Payment.Outcome.TransientFailure, null, "abandoned", NOW);
        var second = p.startAttempt(UUID.randomUUID(), NOW);
        assertEquals(2, second.number());
        p.completeAttempt(second.id(), Payment.Outcome.Succeeded, "pay-1", null, NOW);
        assertEquals("pay-1", p.providerPaymentId());
        assertEquals("order-" + p.orderId().toString().replace("-", ""), p.providerIdempotencyKey());
        assertThrows(DomainException.class, () -> p.completeAttempt(second.id(), Payment.Outcome.Pending, null, null, NOW));
    }

    @Test void staleAndDuplicatePaymentsNeverProduceAnotherEvent() {
        var p = payment();
        assertTrue(p.apply(Payment.Status.Paid, NOW.plusSeconds(1), null, NOW));
        assertFalse(p.apply(Payment.Status.Authorized, NOW, null, NOW));
        assertFalse(p.apply(Payment.Status.Paid, NOW.plusSeconds(2), null, NOW));
        assertEquals(1, p.events().size());
        assertTrue(p.apply(Payment.Status.Refunded, NOW.plusSeconds(3), null, NOW));
        assertThrows(DomainException.class, () -> p.apply(Payment.Status.Paid, NOW.plusSeconds(4), null, NOW));
    }

    @ParameterizedTest @EnumSource(value = Payment.Status.class, names = {"Failed", "Cancelled", "Refunded"})
    void finalPaymentCannotBeReopened(Payment.Status terminal) {
        var p = payment();
        if (terminal == Payment.Status.Refunded) p.apply(Payment.Status.Paid, NOW, null, NOW);
        p.apply(terminal, NOW, "declined", NOW);
        assertThrows(DomainException.class, () -> p.apply(Payment.Status.Authorized, NOW.plusSeconds(1), null, NOW));
        assertThrows(DomainException.class, () -> p.startAttempt(UUID.randomUUID(), NOW));
    }

    @Test void deliveryClassifiesDuplicatesStaleAndConflictingFinalEvents() {
        var d = delivery();
        assertEquals(Delivery.Disposition.Applied, d.apply("evt-delivered", "delivered", Delivery.Status.Delivered, NOW.plusSeconds(10), null, NOW));
        assertEquals(Delivery.Disposition.Duplicate, d.apply("evt-delivered", "delivered", Delivery.Status.Delivered, NOW.plusSeconds(10), null, NOW));
        assertEquals(Delivery.Disposition.Stale, d.apply("evt-pickup", "pickup", Delivery.Status.Pickup, NOW.plusSeconds(5), null, NOW));
        assertEquals(Delivery.Disposition.OutOfOrder, d.apply("evt-late", "pickup", Delivery.Status.Pickup, NOW.plusSeconds(11), null, NOW));
        assertEquals(Delivery.Disposition.Conflict, d.apply("evt-conflict", "returned", Delivery.Status.Returned, NOW.plusSeconds(12), null, NOW));
        assertEquals(Delivery.Status.Delivered, d.status());
        assertEquals(5, d.events().size());
    }

    @Test void deliveryMustBeConfirmedBeforeEventsAndCannotCancelAfterPickup() {
        var q = quote();
        var unconfirmed = new Delivery(UUID.randomUUID(), q, 2, NOW);
        assertEquals("order-" + q.orderId().toString().replace("-", "") + "-delivery-2", unconfirmed.providerIdempotencyKey());
        assertThrows(DomainException.class, () -> unconfirmed.apply("e", "pickup", Delivery.Status.Pickup, NOW, null, NOW));
        assertThrows(DomainException.class, () -> new Delivery(UUID.randomUUID(), q, 1, q.expiresAt()));
        var d = delivery();
        d.apply("e", "pickup_complete", Delivery.Status.PickupComplete, NOW, null, NOW);
        assertThrows(DomainException.class, () -> d.cancel(NOW));
    }

    @Test void courierStoresOnlyMaskedPhone() {
        var courier = Delivery.Courier.from("Test Courier", new PhoneNumber("+5581999990000"), " bicycle ", null, null);
        assertEquals("+55*******0000", courier.phoneMasked());
        assertEquals("bicycle", courier.vehicleType());
    }
}
