package dev.fulfillmenthub.runtime.orders;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="order_status_changes")
public class OrderStatusRow {
 @Id public UUID id; @ManyToOne(optional=false) @JoinColumn(name="order_id") public OrderRow order;
 @Column(name="from_status",nullable=false,length=32) public String from;
 @Column(name="to_status",nullable=false,length=32) public String to;
 @Column(name="occurred_at",nullable=false) public Instant at; @Column(length=200) public String reason;
 public UUID actorUserId; protected OrderStatusRow() {}
}
