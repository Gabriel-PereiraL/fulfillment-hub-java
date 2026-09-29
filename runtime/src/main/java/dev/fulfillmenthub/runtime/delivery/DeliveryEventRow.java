package dev.fulfillmenthub.runtime.delivery;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
@Entity @Table(name="delivery_events") public class DeliveryEventRow{@Id public UUID id;@ManyToOne(optional=false)@JoinColumn(name="delivery_id")public DeliveryRow delivery;
 @Column(nullable=false,length=128)public String providerEventId;@Column(nullable=false,length=40)public String providerStatus;@Column(nullable=false)public Instant occurredAt;
 @Column(nullable=false)public Instant receivedAt;@Column(nullable=false,length=32)public String disposition;protected DeliveryEventRow(){} }
