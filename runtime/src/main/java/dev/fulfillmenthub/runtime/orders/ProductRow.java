package dev.fulfillmenthub.runtime.orders;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "products")
public class ProductRow {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable=false, unique=true, length=32) public String sku;
    @Column(nullable=false, length=120) public String name;
    @Column(name="unit_price_amount", nullable=false, precision=18, scale=2) public BigDecimal amount;
    @Column(name="unit_price_currency", nullable=false, length=3) public String currency;
    @Column(nullable=false) public int stockQuantity;
    @Column(nullable=false) public boolean active;
    @Column(nullable=false) public Instant createdAt;
    @Column(nullable=false) public Instant updatedAt;
    protected ProductRow() {}
    public static ProductRow create(UUID id, String sku, String name, BigDecimal amount, int stock, Instant now) {
        var row = new ProductRow(); row.id=id; row.sku=sku; row.name=name; row.amount=amount;
        row.currency="BRL"; row.stockQuantity=stock; row.active=true; row.createdAt=now; row.updatedAt=now; return row;
    }
}
