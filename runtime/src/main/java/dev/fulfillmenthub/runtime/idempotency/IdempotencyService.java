package dev.fulfillmenthub.runtime.idempotency;
import jakarta.persistence.*; import java.time.*; import org.springframework.dao.DataIntegrityViolationException; import org.springframework.stereotype.Service; import org.springframework.transaction.support.TransactionTemplate;
@Service
public class IdempotencyService {
 public enum State { Started, Replay, Recovered, Mismatch, InProgress }
 public record Result(State state,Integer status,String body,String contentType,String location,java.util.UUID orderId) {}
 private final EntityManager em; private final TransactionTemplate tx; private final Clock clock;
 public IdempotencyService(EntityManager em,TransactionTemplate tx,Clock clock){this.em=em;this.tx=tx;this.clock=clock;}
 public Result begin(String scope,String key,String hash){try{return beginTransaction(scope,key,hash);}catch(DataIntegrityViolationException|PersistenceException race){return beginTransaction(scope,key,hash);}}
 private Result beginTransaction(String scope,String key,String hash){return tx.execute(s->{
  var id=new IdempotencyRow.Key(scope,key); var row=em.find(IdempotencyRow.class,id,LockModeType.PESSIMISTIC_WRITE); var now=clock.instant();
  if(row==null){row=new IdempotencyRow();row.scope=scope;row.key=key;row.requestHash=hash;row.status="InProgress";row.createdAt=now;row.expiresAt=now.plus(Duration.ofHours(24));em.persist(row);em.flush();return new Result(State.Started,null,null,null,null,null);}
  if(!row.requestHash.equals(hash)&&row.expiresAt.isAfter(now))return new Result(State.Mismatch,null,null,null,null,null);
  if(!row.expiresAt.isAfter(now)&&row.orderId==null){row.requestHash=hash;row.status="InProgress";row.responseStatusCode=null;row.responseBody=null;row.responseContentType=null;row.responseLocation=null;row.createdAt=now;row.expiresAt=now.plus(Duration.ofHours(24));return new Result(State.Started,null,null,null,null,null);}
  if(row.status.equals("Completed"))return new Result(State.Replay,row.responseStatusCode,row.responseBody,row.responseContentType,row.responseLocation,row.orderId);
  if(row.orderId!=null)return new Result(State.Recovered,null,null,null,null,row.orderId);
  return new Result(State.InProgress,null,null,null,null,null);});}
 public void complete(String scope,String key,int status,String body,String contentType,String location){tx.executeWithoutResult(s->{
  var row=em.find(IdempotencyRow.class,new IdempotencyRow.Key(scope,key),LockModeType.PESSIMISTIC_WRITE);
  if(row==null)throw new IllegalStateException("Idempotency reservation disappeared.");row.status="Completed";row.responseStatusCode=status;row.responseBody=body;row.responseContentType=contentType;row.responseLocation=location;});}
 public void release(String scope,String key){tx.executeWithoutResult(s->{var row=em.find(IdempotencyRow.class,new IdempotencyRow.Key(scope,key),LockModeType.PESSIMISTIC_WRITE);if(row!=null&&row.status.equals("InProgress")&&row.orderId==null)em.remove(row);});}
}
