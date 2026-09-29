package dev.fulfillmenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Delivery {
    public enum Status { Requested, Pending, Pickup, PickupComplete, Dropoff, Delivered, Cancelled, Returned }
    public enum Disposition { Applied, Duplicate, OutOfOrder, Stale, Conflict }
    public record Quote(UUID id, UUID orderId, String provider, String providerQuoteId, Money fee,
            Instant estimatedDropoffAt, int durationMinutes, int pickupDurationMinutes, Instant expiresAt, Instant createdAt) {
        public Quote {
            Objects.requireNonNull(id);
            Objects.requireNonNull(orderId);
            provider = Address.required(provider, 40);
            providerQuoteId = Address.required(providerQuoteId, 128);
            if (fee.amount().signum() < 0) throw new DomainException("Quote fee cannot be negative.");
            if (!expiresAt.isAfter(createdAt)) throw new DomainException("Quote expiry must be in the future.");
        }
        public boolean expired(Instant now) { return !expiresAt.isAfter(now); }
    }
    public record Courier(String name, String phoneMasked, String vehicleType, Double latitude, Double longitude) {
        public static Courier from(String name, PhoneNumber phone, String vehicleType, Double latitude, Double longitude) {
            return new Courier(Address.required(name, 120), phone == null ? null : phone.masked(),
                    vehicleType == null ? null : vehicleType.trim(), latitude, longitude);
        }
    }
    public record Event(String providerEventId, String providerStatus, Instant occurredAt,
            Instant receivedAt, Disposition disposition) {}

    private final UUID id;
    private final UUID orderId;
    private final UUID quoteId;
    private final String provider;
    private final String providerIdempotencyKey;
    private final Instant createdAt;
    private Instant updatedAt;
    private Money fee;
    private Status status = Status.Requested;
    private String providerDeliveryId;
    private String trackingUrl;
    private Courier courier;
    private Instant lastProviderEventAt;
    private final List<Event> events = new ArrayList<>();

    public Delivery(UUID id, Quote quote, int attempt, Instant now) {
        if (quote.expired(now)) throw new DomainException("Delivery quote has expired.");
        if (attempt < 1) throw new DomainException("Delivery attempt must be positive.");
        this.id = Objects.requireNonNull(id);
        this.orderId = quote.orderId;
        this.quoteId = quote.id;
        this.provider = quote.provider;
        this.providerIdempotencyKey = "order-" + orderId.toString().replace("-", "") + "-delivery-" + attempt;
        this.fee = quote.fee;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void confirm(String remoteId, String tracking, Money actualFee, Instant now) {
        if (status != Status.Requested) throw new DomainException("Delivery has already been confirmed.");
        providerDeliveryId = Address.required(remoteId, 128);
        trackingUrl = tracking;
        fee = actualFee;
        status = Status.Pending;
        updatedAt = now;
    }

    public Disposition apply(String eventId, String remoteStatus, Status reported, Instant occurredAt,
            Courier reportedCourier, Instant now) {
        if (eventId == null || eventId.isBlank()) throw new DomainException("Provider event ID is required.");
        if (status == Status.Requested) throw new DomainException("Unconfirmed delivery cannot receive status events.");
        var disposition = classify(eventId, reported, occurredAt);
        events.add(new Event(eventId, remoteStatus, occurredAt, now, disposition));
        if (disposition == Disposition.Applied) {
            status = reported;
            lastProviderEventAt = occurredAt;
            if (reportedCourier != null) courier = reportedCourier;
        }
        updatedAt = now;
        return disposition;
    }

    private Disposition classify(String eventId, Status reported, Instant occurredAt) {
        if (events.stream().anyMatch(e -> e.providerEventId.equals(eventId))) return Disposition.Duplicate;
        if (reported == Status.Requested) return Disposition.Conflict;
        if (lastProviderEventAt != null && occurredAt.isBefore(lastProviderEventAt)) return Disposition.Stale;
        if (reported == status) return Disposition.Duplicate;
        if (isFinal()) return terminal(reported) ? Disposition.Conflict : Disposition.OutOfOrder;
        return reported == Status.Cancelled || reported == Status.Returned || reported.ordinal() > status.ordinal()
                ? Disposition.Applied : Disposition.OutOfOrder;
    }

    public void cancel(Instant now) {
        if (status == Status.Cancelled) return;
        if (hasBeenPickedUp() || status == Status.Returned) throw new DomainException("Delivery cannot be cancelled after pickup.");
        status = Status.Cancelled;
        updatedAt = now;
    }

    private static boolean terminal(Status status) { return status == Status.Delivered || status == Status.Cancelled || status == Status.Returned; }
    public boolean isFinal() { return terminal(status); }
    public boolean hasBeenPickedUp() { return status == Status.PickupComplete || status == Status.Dropoff || status == Status.Delivered; }
    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID quoteId() { return quoteId; }
    public String provider() { return provider; }
    public String providerIdempotencyKey() { return providerIdempotencyKey; }
    public Status status() { return status; }
    public String providerDeliveryId() { return providerDeliveryId; }
    public String trackingUrl() { return trackingUrl; }
    public Money fee() { return fee; }
    public Courier courier() { return courier; }
    public Instant lastProviderEventAt() { return lastProviderEventAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<Event> events() { return events.stream().sorted(Comparator.comparing(Event::receivedAt).thenComparing(Event::occurredAt)).toList(); }
}
