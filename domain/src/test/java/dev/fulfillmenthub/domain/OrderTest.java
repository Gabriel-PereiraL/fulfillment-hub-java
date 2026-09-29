package dev.fulfillmenthub.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderTest {
    static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    static Product product() { return new Product(UUID.randomUUID(), "SKU", "Product", Money.brl("19.90"), 50, NOW); }
    static Address address() { return new Address("A", "1", null, "D", "C", "PE", "51000000", "BR", null, null); }
    static Order order() { return new Order(UUID.randomUUID(), UUID.randomUUID(), true, address(), List.of(new Order.Line(product(), 2)), "key", NOW); }

    @Test void checkoutFreezesPriceAndCalculatesTotals() {
        var p = product();
        var o = new Order(UUID.randomUUID(), UUID.randomUUID(), true, address(), List.of(new Order.Line(p, 2)), "key", NOW);
        p.changePrice(Money.brl("999"), NOW);
        o.setDeliveryFee(Money.brl("12"), NOW);
        assertEquals(Money.brl("39.8"), o.subtotal());
        assertEquals(Money.brl("51.8"), o.total());
        assertEquals(Money.brl("19.9"), o.items().getFirst().unitPrice());
        assertThrows(UnsupportedOperationException.class, () -> o.items().clear());
    }

    @Test void completeFlowProducesEventsAndHistoryOnce() {
        var o = order();
        var payment = UUID.randomUUID();
        var delivery = UUID.randomUUID();
        o.setDeliveryFee(Money.brl("12"), NOW);
        o.awaitingPayment(payment, NOW); o.awaitingPayment(payment, NOW);
        o.paid(payment, NOW); o.paid(payment, NOW);
        o.deliveryRequested(delivery, Money.brl("12"), NOW);
        o.deliveryRequested(delivery, Money.brl("12"), NOW);
        o.inDelivery(NOW); o.inDelivery(NOW);
        o.delivered(NOW); o.delivered(NOW);
        assertEquals(Order.Status.Delivered, o.status());
        assertEquals(6, o.statusHistory().size());
        assertEquals(List.of("OrderPlaced", "OrderPaid", "OrderDeliveryRequested", "OrderDelivered"), o.events().stream().map(Order.Event::type).toList());
        assertThrows(DomainException.class, () -> o.cancel(Order.CancellationReason.OperatorAction, NOW, null, null));
    }

    @Test void cancellationIsIdempotentAndDoesNotResurrect() {
        var o = order();
        o.cancel(Order.CancellationReason.CustomerRequest, NOW, null, null);
        o.cancel(Order.CancellationReason.CustomerRequest, NOW, null, null);
        assertEquals(2, o.events().size());
        assertEquals(Order.Status.Created, o.events().getLast().previousStatus());
        assertThrows(DomainException.class, () -> o.paid(UUID.randomUUID(), NOW));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 100})
    void rejectsBadQuantities(int quantity) {
        assertThrows(DomainException.class, () -> new Order(UUID.randomUUID(), UUID.randomUUID(), true,
                address(), List.of(new Order.Line(product(), quantity)), "key", NOW));
    }

    @Test void rejectsDuplicateProductsAndEmptyOrder() {
        var line = new Order.Line(product(), 1);
        assertThrows(DomainException.class, () -> new Order(UUID.randomUUID(), UUID.randomUUID(), true, address(), List.of(line, line), "key", NOW));
        assertThrows(DomainException.class, () -> new Order(UUID.randomUUID(), UUID.randomUUID(), true, address(), List.of(), "key", NOW));
    }

    static Stream<Arguments> cancellationCases() {
        return Stream.of(
            Arguments.of(Order.Status.Created, Order.CancellationReason.CustomerRequest, true),
            Arguments.of(Order.Status.AwaitingPayment, Order.CancellationReason.PaymentTimeout, true),
            Arguments.of(Order.Status.Paid, Order.CancellationReason.PaymentFailed, false),
            Arguments.of(Order.Status.Paid, Order.CancellationReason.DeliveryFailed, true),
            Arguments.of(Order.Status.DeliveryRequested, Order.CancellationReason.CustomerRequest, true),
            Arguments.of(Order.Status.InDelivery, Order.CancellationReason.CustomerRequest, false),
            Arguments.of(Order.Status.InDelivery, Order.CancellationReason.OperatorAction, true),
            Arguments.of(Order.Status.Delivered, Order.CancellationReason.OperatorAction, false),
            Arguments.of(Order.Status.Cancelled, Order.CancellationReason.DeliveryFailed, false));
    }

    @ParameterizedTest @MethodSource("cancellationCases")
    void cancellationMatrix(Order.Status status, Order.CancellationReason reason, boolean expected) {
        assertEquals(expected, Order.cancellationAllowed(status, reason));
    }
}
