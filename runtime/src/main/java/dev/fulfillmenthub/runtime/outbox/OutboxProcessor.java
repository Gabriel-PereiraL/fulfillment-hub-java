package dev.fulfillmenthub.runtime.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxProcessor {
 private static final int MAX_ATTEMPTS=5;
 private static final Set<String> KNOWN=Set.of("OrderPlaced","OrderPaid","OrderCancelled","PaymentPaid");
 private final EntityManager em;private final TransactionTemplate tx;private final Clock clock;private final Map<String,OutboxHandler> handlers;
 public OutboxProcessor(EntityManager em,TransactionTemplate tx,Clock clock,List<OutboxHandler> handlers){this.em=em;this.tx=tx;this.clock=clock;
  this.handlers=handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxHandler::type,Function.identity()));}
 public int drain(int batchSize){var claimed=claim(Math.max(1,Math.min(batchSize,50)));for(var message:claimed)dispatch(message);return claimed.size();}
 @SuppressWarnings("unchecked")
 private List<OutboxRow> claim(int limit){return tx.execute(s->em.createNativeQuery("""
  WITH candidates AS (
    SELECT id FROM outbox_messages
    WHERE status='Pending' AND next_attempt_at<=:now AND (locked_until IS NULL OR locked_until<=:now)
    ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
  )
  UPDATE outbox_messages m SET locked_until=:until FROM candidates c WHERE m.id=c.id
  RETURNING m.*
  """,OutboxRow.class).setParameter("now",clock.instant()).setParameter("until",clock.instant().plusSeconds(60))
  .setParameter("limit",limit).getResultList());}
 private void dispatch(OutboxRow claimed){try{var handler=handlers.get(claimed.type);if(handler!=null)handler.handle(claimed);
   else if(!KNOWN.contains(claimed.type))throw new IllegalStateException("Unknown outbox event type: "+claimed.type);markProcessed(claimed.id);
  }catch(Exception failure){markFailed(claimed.id,failure);}}
 private void markProcessed(java.util.UUID id){tx.executeWithoutResult(s->{var row=em.find(OutboxRow.class,id,LockModeType.PESSIMISTIC_WRITE);
  row.status="Processed";row.processedAt=clock.instant();row.lockedUntil=null;row.lastError=null;});}
 private void markFailed(java.util.UUID id,Exception failure){tx.executeWithoutResult(s->{var row=em.find(OutboxRow.class,id,LockModeType.PESSIMISTIC_WRITE);
  row.attempts++;row.lastError=truncate(failure.getClass().getSimpleName()+": "+failure.getMessage(),1000);row.lockedUntil=null;
  if(row.attempts>=MAX_ATTEMPTS)row.status="Failed";else{long cap=Math.min(300,1L<<Math.min(row.attempts,8));
   double equalJitter=0.5+ThreadLocalRandom.current().nextDouble()*0.5;row.nextAttemptAt=clock.instant().plus(Duration.ofMillis((long)(cap*1000*equalJitter)));}});}
 public boolean requeue(java.util.UUID id){return Boolean.TRUE.equals(tx.execute(s->{var row=em.find(OutboxRow.class,id,LockModeType.PESSIMISTIC_WRITE);
  if(row==null)return false;row.status="Pending";row.attempts=0;row.nextAttemptAt=clock.instant();row.lockedUntil=null;row.processedAt=null;row.lastError=null;return true;}));}
 private static String truncate(String value,int max){if(value==null)return "Unknown error";return value.length()>max?value.substring(0,max):value;}
}
