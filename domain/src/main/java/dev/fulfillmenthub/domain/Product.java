package dev.fulfillmenthub.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class Product {
    private final UUID id;
    private final String sku;
    private final String name;
    private Money unitPrice;
    private int stockQuantity;
    private boolean active = true;
    private final Instant createdAt;
    private Instant updatedAt;

    public Product(UUID id, String sku, String name, Money price, int stock, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.sku = Address.required(sku, 32).toUpperCase(Locale.ROOT);
        this.name = Address.required(name, 120);
        requirePrice(price);
        if (stock < 0) throw new DomainException("Initial stock cannot be negative.");
        this.unitPrice = price;
        this.stockQuantity = stock;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public void reserve(int quantity, Instant now) {
        positive(quantity);
        if (!active) throw new DomainException("Inactive product cannot be reserved.");
        if (quantity > stockQuantity) throw new DomainException("Insufficient stock.");
        stockQuantity -= quantity;
        updatedAt = now;
    }

    public void release(int quantity, Instant now) {
        positive(quantity);
        stockQuantity = Math.addExact(stockQuantity, quantity);
        updatedAt = now;
    }

    public void changePrice(Money price, Instant now) {
        requirePrice(price);
        unitPrice = price;
        updatedAt = now;
    }

    public void setActive(boolean value, Instant now) { active = value; updatedAt = now; }
    public UUID id() { return id; }
    public String sku() { return sku; }
    public String name() { return name; }
    public Money unitPrice() { return unitPrice; }
    public int stockQuantity() { return stockQuantity; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    private static void positive(int value) {
        if (value <= 0) throw new DomainException("Quantity must be greater than zero.");
    }
    private static void requirePrice(Money value) {
        if (value.amount().signum() <= 0) throw new DomainException("Product unit price must be positive.");
    }
}
