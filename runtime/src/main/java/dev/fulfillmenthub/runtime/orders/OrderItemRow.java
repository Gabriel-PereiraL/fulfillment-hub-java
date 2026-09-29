package dev.fulfillmenthub.runtime.orders;
import jakarta.persistence.*; import java.math.BigDecimal; import java.util.UUID;
@Entity @Table(name="order_items")
public class OrderItemRow {
 @Id public UUID id; @ManyToOne(optional=false) @JoinColumn(name="order_id") public OrderRow order;
 @Column(nullable=false) public UUID productId; @Column(nullable=false,length=32) public String sku;
 @Column(nullable=false,length=120) public String productName;
 @Column(name="unit_price_amount",nullable=false,precision=18,scale=2) public BigDecimal amount;
 @Column(name="unit_price_currency",nullable=false,length=3) public String currency;
 @Column(nullable=false) public int quantity; protected OrderItemRow() {}
}
