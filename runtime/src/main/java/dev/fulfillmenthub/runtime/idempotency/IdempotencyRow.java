package dev.fulfillmenthub.runtime.idempotency;
import jakarta.persistence.*; import java.io.Serial; import java.io.Serializable; import java.time.Instant; import java.util.Objects;
@Entity @Table(name="idempotency_records") @IdClass(IdempotencyRow.Key.class)
public class IdempotencyRow {
 @Id @Column(length=64) public String scope; @Id @Column(length=64) public String key; @Version public long version;
 @Column(nullable=false,length=64) public String requestHash; @Column(nullable=false,length=16) public String status;
 public Integer responseStatusCode; @Column(columnDefinition="text") public String responseBody;
 @Column(length=128) public String responseContentType; @Column(length=512) public String responseLocation;
 @Column(nullable=false) public Instant createdAt; @Column(nullable=false) public Instant expiresAt;
 protected IdempotencyRow() {}
 public static final class Key implements Serializable { public String scope; public String key; public Key() {}
  @Serial private static final long serialVersionUID = 1L;
  public Key(String scope,String key){this.scope=scope;this.key=key;}
  @Override public boolean equals(Object o){return o instanceof Key k&&Objects.equals(scope,k.scope)&&Objects.equals(key,k.key);}
  @Override public int hashCode(){return Objects.hash(scope,key);} }
}
