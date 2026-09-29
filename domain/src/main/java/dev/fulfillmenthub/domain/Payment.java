package dev.fulfillmenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Payment {
    public enum Status { Pending, Authorized, Paid, Failed, Cancelled, Refunded }
    public enum Outcome { Pending, Succeeded, TransientFailure, PermanentFailure }
    public record Attempt(UUID id, int number, Outcome outcome, String reference, String errorCode,
            Instant startedAt, Instant completedAt) {}
    public record PaidEvent(UUID paymentId, UUID orderId, Instant occurredAt) {}

    private final UUID id;
    private final UUID orderId;
    private final Money amount;
    private final String provider;
    private final String providerIdempotencyKey;
    private final Instant createdAt;
    private Instant updatedAt;
    private Status status = Status.Pending;
    private String providerPaymentId;
    private String failureReason;
    private Instant lastProviderEventAt;
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<PaidEvent> events = new ArrayList<>();

    public Payment(UUID id, UUID orderId, Money amount, String provider, Instant now) {
        if (amount.amount().signum() <= 0) throw new DomainException("Payment amount must be positive.");
        this.id = Objects.requireNonNull(id);
        this.orderId = Objects.requireNonNull(orderId);
        this.amount = amount;
        this.provider = Address.required(provider, 40);
        this.providerIdempotencyKey = "order-" + orderId.toString().replace("-", "");
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Attempt startAttempt(UUID attemptId, Instant now) {
        if (status != Status.Pending && status != Status.Authorized) throw new DomainException("Payment cannot start another attempt.");
        if (attempts.stream().anyMatch(a -> a.outcome == Outcome.Pending)) throw new DomainException("A payment attempt is already pending.");
        var attempt = new Attempt(attemptId, attempts.size() + 1, Outcome.Pending, null, null, now, null);
        attempts.add(attempt);
        updatedAt = now;
        return attempt;
    }

    public void completeAttempt(UUID attemptId, Outcome outcome, String reference, String error, Instant now) {
        if (outcome == Outcome.Pending) throw new DomainException("An attempt cannot complete as pending.");
        for (int i = 0; i < attempts.size(); i++) {
            var prior = attempts.get(i);
            if (!prior.id.equals(attemptId)) continue;
            attempts.set(i, new Attempt(attemptId, prior.number, outcome, truncate(reference, 128), truncate(error, 64), prior.startedAt, now));
            if (outcome == Outcome.Succeeded && reference != null) providerPaymentId = reference;
            updatedAt = now;
            return;
        }
        throw new DomainException("Attempt does not belong to this payment.");
    }

    public boolean apply(Status reported, Instant occurredAt, String failure, Instant now) {
        if (lastProviderEventAt != null && occurredAt.isBefore(lastProviderEventAt)) return false;
        if (reported == status) { lastProviderEventAt = occurredAt; return false; }
        boolean allowed = switch (status) {
            case Pending -> reported == Status.Authorized || reported == Status.Paid || reported == Status.Failed || reported == Status.Cancelled;
            case Authorized -> reported == Status.Paid || reported == Status.Failed || reported == Status.Cancelled;
            case Paid -> reported == Status.Refunded;
            case Failed, Cancelled, Refunded -> false;
        };
        if (!allowed) throw new DomainException("Invalid payment state transition.");
        status = reported;
        lastProviderEventAt = occurredAt;
        updatedAt = now;
        if (reported == Status.Failed) {
            var reason = failure == null ? "Provider reported failure" : failure;
            failureReason = reason.substring(0, Math.min(200, reason.length()));
        }
        if (reported == Status.Paid) events.add(new PaidEvent(id, orderId, now));
        return true;
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public Money amount() { return amount; }
    public String provider() { return provider; }
    public String providerIdempotencyKey() { return providerIdempotencyKey; }
    public Status status() { return status; }
    public String providerPaymentId() { return providerPaymentId; }
    public String failureReason() { return failureReason; }
    public Instant lastProviderEventAt() { return lastProviderEventAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<Attempt> attempts() { return List.copyOf(attempts); }
    public List<PaidEvent> events() { return List.copyOf(events); }
    public void clearEvents() { events.clear(); }
    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }
}
