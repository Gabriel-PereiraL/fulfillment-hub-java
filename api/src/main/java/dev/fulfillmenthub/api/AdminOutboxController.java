package dev.fulfillmenthub.api;
import dev.fulfillmenthub.runtime.outbox.*; import jakarta.persistence.EntityManager; import java.time.Instant; import java.util.*;
import org.springframework.http.ResponseEntity; import org.springframework.transaction.annotation.Transactional; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/outbox")
public class AdminOutboxController {
 public record MessageDto(UUID id,String type,UUID aggregateId,String status,int attempts,Instant occurredAt,Instant nextAttemptAt,Instant processedAt,String lastError,String correlationId) {}
 private final EntityManager em;private final OutboxProcessor processor;public AdminOutboxController(EntityManager em,OutboxProcessor processor){this.em=em;this.processor=processor;}
 @GetMapping @Transactional(readOnly=true) public List<MessageDto> list(@RequestParam(defaultValue="Failed") String status,@RequestParam(defaultValue="50") int limit){
  if(!Set.of("Pending","Processed","Failed").contains(status))throw new IllegalArgumentException("Invalid outbox status.");
  return em.createQuery("select o from OutboxRow o where o.status=:status order by o.occurredAt desc",OutboxRow.class)
   .setParameter("status",status).setMaxResults(Math.max(1,Math.min(limit,200))).getResultList().stream().map(AdminOutboxController::dto).toList();}
 @PostMapping("/{id}/retry") public ResponseEntity<MessageDto> retry(@PathVariable("id") UUID id){if(!processor.requeue(id))return ResponseEntity.notFound().build();
  return ResponseEntity.ok(emView(id));}
 private MessageDto emView(UUID id){return dto(em.find(OutboxRow.class,id));}
 private static MessageDto dto(OutboxRow o){return new MessageDto(o.id,o.type,o.aggregateId,o.status,o.attempts,o.occurredAt,o.nextAttemptAt,o.processedAt,o.lastError,o.correlationId);}
}
