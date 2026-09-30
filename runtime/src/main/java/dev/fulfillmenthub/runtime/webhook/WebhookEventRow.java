package dev.fulfillmenthub.runtime.webhook;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;import org.hibernate.annotations.JdbcTypeCode;import org.hibernate.type.SqlTypes;
@Entity @Table(name="webhook_events",uniqueConstraints=@UniqueConstraint(columnNames={"provider","provider_event_id"})) public class WebhookEventRow{@Id public UUID id;@Version public long version;
 @Column(nullable=false,length=40)public String provider;@Column(nullable=false,length=128)public String providerEventId;@Column(nullable=false,length=64)public String eventType;
 @JdbcTypeCode(SqlTypes.JSON)@Column(nullable=false,columnDefinition="jsonb")public String payload;@Column(nullable=false,length=16)public String status;@Column(nullable=false)public Instant receivedAt;public Instant processedAt;
 @Column(nullable=false)public int attempts;@Column(length=500)public String lastError;@Column(length=64)public String correlationId;public UUID owner;public Instant lockedUntil;@Column(nullable=false)public Instant nextAttemptAt;protected WebhookEventRow(){} }
