package dev.fulfillmenthub.runtime.webhook;
import jakarta.persistence.EntityManager;import java.nio.charset.StandardCharsets;import java.security.*;import java.time.Clock;import java.util.HexFormat;import java.util.UUID;
import javax.crypto.Mac;import javax.crypto.spec.SecretKeySpec;import org.springframework.dao.DataIntegrityViolationException;import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;import tools.jackson.core.JacksonException;import tools.jackson.databind.ObjectMapper;
@Service public class WebhookInboxService {
 public enum Verification{Valid,MissingTimestamp,StaleTimestamp,MissingSignature,InvalidSignature}
 public record Identity(String id,String type){} public record Receipt(UUID id,boolean duplicate){}
 private final EntityManager em;private final TransactionTemplate tx;private final Clock clock;private final ObjectMapper json;
 public WebhookInboxService(EntityManager em,TransactionTemplate tx,Clock clock,ObjectMapper json){this.em=em;this.tx=tx;this.clock=clock;this.json=json;}
 public Verification verify(String key,byte[] body,String signature,String timestamp,long tolerance){if(timestamp==null)return Verification.MissingTimestamp;
  long seconds;try{seconds=Long.parseLong(timestamp);}catch(NumberFormatException e){return Verification.MissingTimestamp;}
  if(Math.abs(clock.instant().getEpochSecond()-seconds)>tolerance)return Verification.StaleTimestamp;if(signature==null)return Verification.MissingSignature;
  byte[] supplied;try{supplied=HexFormat.of().parseHex(signature);}catch(IllegalArgumentException e){return Verification.InvalidSignature;}
  try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
   mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));mac.update((byte)'.');return MessageDigest.isEqual(mac.doFinal(body),supplied)?Verification.Valid:Verification.InvalidSignature;
  }catch(GeneralSecurityException impossible){throw new IllegalStateException("HMAC-SHA256 unavailable",impossible);}}
 public Identity identify(byte[] body)throws JacksonException{var node=json.readTree(body);if(node==null)return null;var id=node.get("id");var type=node.get("type");
  if(type==null)type=node.get("kind");var idValue=id==null?null:id.stringValue();var typeValue=type==null?null:type.stringValue();
  if(idValue==null||typeValue==null||idValue.isBlank()||typeValue.isBlank())return null;return new Identity(idValue,typeValue);}
 public Receipt store(String provider,Identity identity,byte[] body,String correlation){
  try{return tx.execute(s->{var existing=em.createQuery("select w.id from WebhookEventRow w where w.provider=:p and w.providerEventId=:e",UUID.class)
    .setParameter("p",provider).setParameter("e",identity.id()).getResultStream().findFirst().orElse(null);if(existing!=null)return new Receipt(existing,true);
   var row=new WebhookEventRow();row.id=UUID.randomUUID();row.provider=provider;row.providerEventId=identity.id();row.eventType=identity.type();
   row.payload=new String(body,StandardCharsets.UTF_8);row.status="Received";row.receivedAt=clock.instant();row.correlationId=correlation;em.persist(row);em.flush();return new Receipt(row.id,false);});
  }catch(DataIntegrityViolationException duplicate){var id=tx.execute(s->em.createQuery("select w.id from WebhookEventRow w where w.provider=:p and w.providerEventId=:e",UUID.class)
    .setParameter("p",provider).setParameter("e",identity.id()).getSingleResult());return new Receipt(id,true);}}
}
