package dev.fulfillmenthub.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Customer {
    public record SavedAddress(UUID id, String label, Address address, boolean isDefault) {}
    private final UUID id;
    private final UUID userId;
    private final String name;
    private final EmailAddress email;
    private final PhoneNumber phone;
    private final Instant createdAt;
    private Instant updatedAt;
    private boolean active = true;
    private final List<SavedAddress> addresses = new ArrayList<>();

    public Customer(UUID id, UUID userId, String name, EmailAddress email, PhoneNumber phone, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.name = Address.required(name, 120);
        this.email = Objects.requireNonNull(email);
        this.phone = Objects.requireNonNull(phone);
        this.createdAt = now;
        this.updatedAt = now;
    }
    public SavedAddress addAddress(UUID addressId, String label, Address address, Instant now) {
        if (addresses.size() >= 5) throw new DomainException("A customer can have at most five addresses.");
        var entry = new SavedAddress(addressId, Address.required(label, 40), Objects.requireNonNull(address), addresses.isEmpty());
        addresses.add(entry);
        updatedAt = now;
        return entry;
    }
    public void removeAddress(UUID addressId, Instant now) {
        var prior = findAddress(addressId);
        addresses.remove(prior);
        if (prior.isDefault && !addresses.isEmpty()) setDefaultAddress(addresses.getFirst().id, now);
        updatedAt = now;
    }
    public void setDefaultAddress(UUID addressId, Instant now) {
        findAddress(addressId);
        addresses.replaceAll(a -> new SavedAddress(a.id, a.label, a.address, a.id.equals(addressId)));
        updatedAt = now;
    }
    private SavedAddress findAddress(UUID addressId) {
        return addresses.stream().filter(a -> a.id.equals(addressId)).findFirst()
                .orElseThrow(() -> new DomainException("Address does not belong to customer."));
    }
    public void deactivate(Instant now) { active = false; updatedAt = now; }
    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String name() { return name; }
    public EmailAddress email() { return email; }
    public PhoneNumber phone() { return phone; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<SavedAddress> addresses() { return List.copyOf(addresses); }
}
