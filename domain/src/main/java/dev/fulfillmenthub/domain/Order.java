package dev.fulfillmenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Order {
    public enum Status { Created, AwaitingPayment, Paid, DeliveryRequested, InDelivery, Delivered, Cancelled }
    public enum CancellationReason { CustomerRequest, PaymentFailed, PaymentTimeout, StockUnavailable, DeliveryFailed, OperatorAction }
    public record Line(Product product, int quantity) {}
    public record Item(UUID productId, String sku, String productName, Money unitPrice, int quantity) {
        public Money lineTotal() { return unitPrice.multiply(quantity); }
    }
    public record StatusChange(Status from, Status to, Instant at, String reason, UUID actorUserId) {}
    public record Event(String type, UUID orderId, UUID referenceId, Instant occurredAt,
            CancellationReason cancellationReason, Status previousStatus) {}

    private final UUID id;
    private final UUID customerId;
    private final Address deliveryAddress;
    private final String idempotencyKey;
    private final List<Item> items;
    private final List<StatusChange> history = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Instant createdAt;
    private Instant updatedAt;
    private final Money subtotal;
    private Money deliveryFee;
    private Money total;
    private Status status = Status.Created;
    private CancellationReason cancellationReason;
    private UUID paymentId;
    private UUID deliveryId;

    public Order(UUID id, UUID customerId, boolean customerActive, Address address,
            List<Line> lines, String key, Instant now) {
        if (!customerActive) throw new DomainException("Inactive customer cannot place orders.");
        if (lines == null || lines.isEmpty() || lines.size() > 50) {
            throw new DomainException("An order must have between 1 and 50 items.");
        }
        if (lines.stream().map(l -> l.product().id()).distinct().count() != lines.size()) {
            throw new DomainException("An order cannot contain the same product more than once.");
        }
        if (key != null && key.length() > 64) throw new DomainException("Idempotency key is too long.");
        this.id = Objects.requireNonNull(id);
        this.customerId = Objects.requireNonNull(customerId);
        this.deliveryAddress = Objects.requireNonNull(address);
        this.idempotencyKey = key;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
        this.items = lines.stream().map(line -> {
            if (line.quantity() < 1 || line.quantity() > 99) throw new DomainException("Item quantity must be between 1 and 99.");
            var p = line.product();
            if (!p.active()) throw new DomainException("Inactive product cannot be ordered.");
            return new Item(p.id(), p.sku(), p.name(), p.unitPrice(), line.quantity());
        }).toList();
        this.subtotal = items.stream().map(Item::lineTotal).reduce(Money::add).orElseThrow();
        this.total = subtotal;
        history.add(new StatusChange(Status.Created, Status.Created, now, "Order placed", null));
        raise("OrderPlaced", null, now, null, null);
    }

    public void setDeliveryFee(Money fee, Instant now) {
        if (status != Status.Created) throw new DomainException("Delivery fee can only be set before payment.");
        requireFee(fee);
        var newTotal = subtotal.add(fee);
        deliveryFee = fee;
        total = newTotal;
        updatedAt = now;
    }

    public void awaitingPayment(UUID payment, Instant now) {
        if (status == Status.AwaitingPayment && Objects.equals(paymentId, payment)) return;
        transition(Status.AwaitingPayment, now, null, null);
        paymentId = payment;
    }

    public void paid(UUID payment, Instant now) {
        if (status == Status.Paid) return;
        transition(Status.Paid, now, null, null);
        paymentId = payment;
        raise("OrderPaid", payment, now, null, null);
    }

    public void deliveryRequested(UUID delivery, Money chargedFee, Instant now) {
        if (status == Status.DeliveryRequested && Objects.equals(deliveryId, delivery)) return;
        requireFee(chargedFee);
        var newTotal = subtotal.add(chargedFee);
        transition(Status.DeliveryRequested, now, null, null);
        deliveryId = delivery;
        deliveryFee = chargedFee;
        total = newTotal;
        raise("OrderDeliveryRequested", delivery, now, null, null);
    }

    public void inDelivery(Instant now) {
        if (status != Status.InDelivery) transition(Status.InDelivery, now, null, null);
    }

    public void delivered(Instant now) {
        if (status == Status.Delivered) return;
        transition(Status.Delivered, now, null, null);
        raise("OrderDelivered", null, now, null, null);
    }

    public boolean canCancel(CancellationReason reason) {
        return status == Status.Cancelled || cancellationAllowed(status, reason);
    }

    public void cancel(CancellationReason reason, Instant now, UUID actor, String note) {
        if (status == Status.Cancelled) return;
        if (!cancellationAllowed(status, reason)) throw new DomainException("Order cannot be cancelled with this reason.");
        var previous = status;
        transition(Status.Cancelled, now, note == null ? reason.name() : note, actor);
        cancellationReason = reason;
        raise("OrderCancelled", null, now, reason, previous);
    }

    public static boolean cancellationAllowed(Status status, CancellationReason reason) {
        return switch (reason) {
            case CustomerRequest -> status == Status.Created || status == Status.AwaitingPayment
                    || status == Status.Paid || status == Status.DeliveryRequested;
            case PaymentFailed, PaymentTimeout -> status == Status.Created || status == Status.AwaitingPayment;
            case StockUnavailable -> status == Status.Created;
            case DeliveryFailed -> status == Status.Paid || status == Status.DeliveryRequested || status == Status.InDelivery;
            case OperatorAction -> status != Status.Delivered && status != Status.Cancelled;
        };
    }

    private void transition(Status next, Instant now, String reason, UUID actor) {
        boolean allowed = switch (status) {
            case Created -> next == Status.AwaitingPayment || next == Status.Cancelled;
            case AwaitingPayment -> next == Status.Paid || next == Status.Cancelled;
            case Paid -> next == Status.DeliveryRequested || next == Status.Cancelled;
            case DeliveryRequested -> next == Status.InDelivery || next == Status.Cancelled;
            case InDelivery -> next == Status.Delivered || next == Status.Cancelled;
            case Delivered, Cancelled -> false;
        };
        if (!allowed) throw new DomainException("Invalid order state transition.");
        history.add(new StatusChange(status, next, now, reason, actor));
        status = next;
        updatedAt = now;
    }

    private static void requireFee(Money fee) {
        if (fee.amount().signum() < 0) throw new DomainException("Delivery fee cannot be negative.");
    }
    private void raise(String type, UUID reference, Instant now, CancellationReason reason, Status previous) {
        events.add(new Event(type, id, reference, now, reason, previous));
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public Address deliveryAddress() { return deliveryAddress; }
    public String idempotencyKey() { return idempotencyKey; }
    public List<Item> items() { return items; }
    public List<StatusChange> statusHistory() { return history.stream().sorted(Comparator.comparing(StatusChange::at)).toList(); }
    public List<Event> events() { return List.copyOf(events); }
    public void clearEvents() { events.clear(); }
    public Status status() { return status; }
    public CancellationReason cancellationReason() { return cancellationReason; }
    public Money subtotal() { return subtotal; }
    public Money total() { return total; }
    public Money deliveryFee() { return deliveryFee; }
    public UUID paymentId() { return paymentId; }
    public UUID deliveryId() { return deliveryId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
