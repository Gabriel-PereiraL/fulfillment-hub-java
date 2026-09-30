package dev.fulfillmenthub.runtime.outbox;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="outbox_messages")
public class OutboxRow {
 @Id public UUID id; @Version public long version; @Column(nullable=false,length=100) public String type;
 @Column(nullable=false,columnDefinition="text") public String payload; @Column(nullable=false) public UUID aggregateId;
 @Column(nullable=false) public Instant occurredAt; @Column(nullable=false) public Instant createdAt;
 @Column(nullable=false,length=16) public String status; @Column(nullable=false) public int attempts;
 @Column(nullable=false) public Instant nextAttemptAt; public Instant lockedUntil; public Instant processedAt;
 public UUID owner;
 @Column(length=1000) public String lastError; @Column(length=64) public String correlationId;
 @Column(length=128) public String traceParent; protected OutboxRow() {}
 public static OutboxRow pending(UUID aggregateId, Instant now) { return pending("OrderPlaced",aggregateId,now); }
 public static OutboxRow pending(String type,UUID aggregateId, Instant now) { var r=new OutboxRow(); r.id=UUID.randomUUID();
  r.type=type; r.payload="{\"orderId\":\""+aggregateId+"\"}"; r.aggregateId=aggregateId;
  r.occurredAt=now; r.createdAt=now; r.status="Pending"; r.nextAttemptAt=now; return r; }
}
