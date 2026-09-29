package dev.fulfillmenthub.runtime.delivery;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.Instant;import java.util.*;
@Entity @Table(name="deliveries") public class DeliveryRow{@Id public UUID id;@Version public long version;@Column(nullable=false)public UUID orderId;@Column(nullable=false)public UUID quoteId;
 @Column(nullable=false,length=40)public String provider;@Column(length=128)public String providerDeliveryId;@Column(nullable=false,unique=true,length=128)public String providerIdempotencyKey;
 @Column(nullable=false,length=32)public String status;@Column(name="fee_amount",nullable=false,precision=18,scale=2)public BigDecimal fee;@Column(name="fee_currency",nullable=false,length=3)public String currency;
 @Column(length=512)public String trackingUrl;public Instant lastProviderEventAt;@Column(length=120)public String courierName;@Column(length=16)public String courierPhoneMasked;
 @Column(length=40)public String courierVehicleType;public Double courierLatitude;public Double courierLongitude;@Column(nullable=false)public Instant createdAt;@Column(nullable=false)public Instant updatedAt;
 @OneToMany(mappedBy="delivery",cascade=CascadeType.ALL,orphanRemoval=true)public List<DeliveryEventRow> events=new ArrayList<>();protected DeliveryRow(){} }
