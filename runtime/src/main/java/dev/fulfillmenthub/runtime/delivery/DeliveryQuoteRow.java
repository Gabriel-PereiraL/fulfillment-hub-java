package dev.fulfillmenthub.runtime.delivery;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.Instant;import java.util.UUID;
@Entity @Table(name="delivery_quotes") public class DeliveryQuoteRow{@Id public UUID id;@Column(nullable=false)public UUID orderId;@Column(nullable=false,length=40)public String provider;
 @Column(nullable=false,length=128)public String providerQuoteId;@Column(name="fee_amount",nullable=false,precision=18,scale=2)public BigDecimal fee;
 @Column(name="fee_currency",nullable=false,length=3)public String currency;@Column(nullable=false)public Instant estimatedDropoffAt;@Column(nullable=false)public int durationMinutes;
 @Column(nullable=false)public int pickupDurationMinutes;@Column(nullable=false)public Instant expiresAt;@Column(nullable=false)public Instant createdAt;protected DeliveryQuoteRow(){} }
