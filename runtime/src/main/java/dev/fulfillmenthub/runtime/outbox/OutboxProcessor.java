package dev.fulfillmenthub.runtime.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxProcessor {
 private static final int MAX_ATTEMPTS=5;
 private final EntityManager em;private final TransactionTemplate tx;private final Clock clock;private final Map<String,OutboxHandler> handlers;
 public OutboxProcessor(EntityManager em,TransactionTemplate tx,Clock clock,List<OutboxHandler> handlers){this.em=em;this.tx=tx;this.clock=clock;
  this.handlers=handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxHandler::type,Function.identity()));}
 public int drain(int batchSize){var claimed=claim(Math.max(1,Math.min(batchSize,10)));for(var message:claimed){renew(message.id,message.owner);dispatch(message);}return claimed.size();}
 @SuppressWarnings("unchecked")
 private List<OutboxRow> claim(int limit){var owner=java.util.UUID.randomUUID();return tx.execute(s->em.createNativeQuery("""
  WITH candidates AS (
    SELECT id FROM outbox_messages
    WHERE status='Pending' AND next_attempt_at<=:now AND (locked_until IS NULL OR locked_until<=:now)
    ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
  )
  UPDATE outbox_messages m SET locked_until=:until,owner=:owner FROM candidates c WHERE m.id=c.id
  RETURNING m.*
  """,OutboxRow.class).setParameter("now",clock.instant()).setParameter("until",clock.instant().plusSeconds(60))
  .setParameter("owner",owner).setParameter("limit",limit).getResultList());}
 private void dispatch(OutboxRow claimed){try{var handler=handlers.get(claimed.type);if(handler==null)throw new IllegalStateException("No handler for required outbox event: "+claimed.type);handler.handle(claimed);markProcessed(claimed.id,claimed.owner);
  }catch(Exception failure){markFailed(claimed.id,claimed.owner,failure);}}
 private void renew(java.util.UUID id,java.util.UUID owner){tx.executeWithoutResult(s->em.createNativeQuery("update outbox_messages set locked_until=:until where id=:id and owner=:owner and status='Pending'").setParameter("until",clock.instant().plusSeconds(60)).setParameter("id",id).setParameter("owner",owner).executeUpdate());}
 private void markProcessed(java.util.UUID id,java.util.UUID owner){tx.executeWithoutResult(s->{var row=em.find(OutboxRow.class,id,LockModeType.PESSIMISTIC_WRITE);if(row==null||!owner.equals(row.owner))return;
  row.status="Processed";row.processedAt=clock.instant();row.lockedUntil=null;row.owner=null;row.lastError=null;});}
 private void markFailed(java.util.UUID id,java.util.UUID owner,Exception failure){tx.executeWithoutResult(s->{var row=em.find(OutboxRow.class,id,LockModeType.PESSIMISTIC_WRITE);if(row==null||!owner.equals(row.owner))return;
  row.attempts++;row.lastError=truncate(failure.getClass().getSimpleName()+": "+failure.getMessage(),1000);row.lockedUntil=null;
  row.owner=null;
  if(row.attempts>=MAX_ATTEMPTS)row.status="Failed";else{long cap=Math.min(300,1L<<Math.min(row.attempts,8));
   double equalJitter=0.5+ThreadLocalRandom.current().nextDouble()*0.5;row.nextAttemptAt=clock.instant().plus(Duration.ofMillis((long)(cap*1000*equalJitter)));}});}
 public boolean requeue(java.util.UUID id){return Boolean.TRUE.equals(tx.execute(s->{var row=em.find(OutboxRow.class,id,LockModeType.PESSIMISTIC_WRITE);
  if(row==null)return false;row.status="Pending";row.attempts=0;row.nextAttemptAt=clock.instant();row.lockedUntil=null;row.owner=null;row.processedAt=null;row.lastError=null;return true;}));}
 private static String truncate(String value,int max){if(value==null)return "Unknown error";return value.length()>max?value.substring(0,max):value;}
}
