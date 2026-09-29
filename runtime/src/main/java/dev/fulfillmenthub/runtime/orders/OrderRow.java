package dev.fulfillmenthub.runtime.orders;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity @Table(name="orders")
public class OrderRow {
    @Id public UUID id; @Version public long version;
    @Column(insertable=false, updatable=false) public long number;
    @Column(nullable=false) public UUID customerId;
    @Column(nullable=false, length=32) public String status;
    @Column(length=32) public String cancellationReason;
    public UUID paymentId; public UUID deliveryId;
    @Column(length=64) public String idempotencyKey;
    @Column(nullable=false) public Instant createdAt;
    @Column(nullable=false) public Instant updatedAt;
    @Column(name="delivery_address_street", nullable=false, length=200) public String street;
    @Column(name="delivery_address_number", nullable=false, length=20) public String addressNumber;
    @Column(name="delivery_address_complement", length=100) public String complement;
    @Column(name="delivery_address_district", nullable=false, length=100) public String district;
    @Column(name="delivery_address_city", nullable=false, length=100) public String city;
    @Column(name="delivery_address_state", nullable=false, length=50) public String state;
    @Column(name="delivery_address_postal_code", nullable=false, length=10) public String postalCode;
    @Column(name="delivery_address_country", nullable=false, length=2) public String country;
    @Column(name="delivery_address_latitude") public Double latitude;
    @Column(name="delivery_address_longitude") public Double longitude;
    @Column(name="subtotal_amount", nullable=false, precision=18, scale=2) public BigDecimal subtotal;
    @Column(name="subtotal_currency", nullable=false, length=3) public String subtotalCurrency;
    @Column(name="delivery_fee_amount", precision=18, scale=2) public BigDecimal deliveryFee;
    @Column(name="delivery_fee_currency", length=3) public String deliveryFeeCurrency;
    @Column(name="total_amount", nullable=false, precision=18, scale=2) public BigDecimal total;
    @Column(name="total_currency", nullable=false, length=3) public String totalCurrency;
    @OneToMany(mappedBy="order", cascade=CascadeType.ALL, orphanRemoval=true) public List<OrderItemRow> items = new ArrayList<>();
    @OneToMany(mappedBy="order", cascade=CascadeType.ALL, orphanRemoval=true) public List<OrderStatusRow> history = new ArrayList<>();
    protected OrderRow() {}
}
