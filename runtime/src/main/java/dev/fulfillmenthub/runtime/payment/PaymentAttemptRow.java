package dev.fulfillmenthub.runtime.payment;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
@Entity @Table(name="payment_attempts") public class PaymentAttemptRow{@Id public UUID id;@ManyToOne(optional=false)@JoinColumn(name="payment_id")public PaymentRow payment;
 @Column(nullable=false)public int attemptNumber;@Column(nullable=false)public Instant startedAt;public Instant completedAt;@Column(length=32)public String outcome;
 @Column(length=128)public String providerReference;@Column(length=64)public String errorCode;protected PaymentAttemptRow(){} }
