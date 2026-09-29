package dev.fulfillmenthub.runtime.messaging;
import jakarta.persistence.*;import java.io.*;import java.time.Instant;import java.util.Objects;
@Entity @Table(name="processed_messages") @IdClass(ProcessedMessageRow.Key.class) public class ProcessedMessageRow{@Id @Column(length=100)public String consumer;
 @Id @Column(length=128)public String messageId;@Column(nullable=false)public Instant processedAt;protected ProcessedMessageRow(){}
 public static ProcessedMessageRow create(String consumer,String messageId,Instant at){var r=new ProcessedMessageRow();r.consumer=consumer;r.messageId=messageId;r.processedAt=at;return r;}
 public static class Key implements Serializable{@Serial private static final long serialVersionUID=1L;public String consumer;public String messageId;public Key(){}public Key(String c,String m){consumer=c;messageId=m;}
  @Override public boolean equals(Object o){return o instanceof Key k&&Objects.equals(consumer,k.consumer)&&Objects.equals(messageId,k.messageId);}@Override public int hashCode(){return Objects.hash(consumer,messageId);}}
}
