package dev.fulfillmenthub.runtime.payment;
import jakarta.persistence.*;import java.math.BigDecimal;import java.time.Instant;import java.util.*;
@Entity @Table(name="payments") public class PaymentRow{@Id public UUID id;@Version public long version;@Column(nullable=false)public UUID orderId;
 @Column(nullable=false,length=32)public String status;@Column(nullable=false,length=40)public String provider;@Column(length=128)public String providerPaymentId;
 @Column(nullable=false,unique=true,length=128)public String providerIdempotencyKey;@Column(length=200)public String failureReason;public Instant lastProviderEventAt;
 @Column(name="amount_amount",nullable=false,precision=18,scale=2)public BigDecimal amount;@Column(name="amount_currency",nullable=false,length=3)public String currency;
 @Column(nullable=false)public Instant createdAt;@Column(nullable=false)public Instant updatedAt;
 @OneToMany(mappedBy="payment",cascade=CascadeType.ALL,orphanRemoval=true)public List<PaymentAttemptRow> attempts=new ArrayList<>();protected PaymentRow(){} }
