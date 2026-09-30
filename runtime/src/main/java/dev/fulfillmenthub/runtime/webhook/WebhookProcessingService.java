package dev.fulfillmenthub.runtime.webhook;

import dev.fulfillmenthub.runtime.payment.PaymentGatewayClient;
import dev.fulfillmenthub.runtime.delivery.DeliveryWorkflowService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class WebhookProcessingService {
    private final EntityManager em;private final TransactionTemplate tx;private final Clock clock;private final ObjectMapper json;
    private final ObjectProvider<PaymentGatewayClient> payments;
    private final ObjectProvider<DeliveryWorkflowService> deliveries;
    public WebhookProcessingService(EntityManager em,TransactionTemplate tx,Clock clock,ObjectMapper json,ObjectProvider<PaymentGatewayClient> payments,ObjectProvider<DeliveryWorkflowService> deliveries){
        this.em=em;this.tx=tx;this.clock=clock;this.json=json;this.payments=payments;this.deliveries=deliveries;}
    @SuppressWarnings("unchecked") public int drain(int limit){var ids=tx.execute(s->(java.util.List<UUID>)em.createNativeQuery("""
            select id from webhook_events where status in ('Received','Failed','Processing') and next_attempt_at<=:now
            and (attempts<5 or (status='Processing' and locked_until<=:now))
            and (locked_until is null or locked_until<=:now) order by received_at,id limit :limit""").setParameter("now",clock.instant()).setParameter("limit",Math.max(1,Math.min(50,limit))).getResultList());
        if(ids==null)return 0;int processed=0;for(var id:ids)if(processOne(id))processed++;return processed;}
    @SuppressWarnings("unchecked") public boolean processOne(UUID id){var owner=UUID.randomUUID();var snapshot=tx.execute(s->{var claimed=(java.util.List<WebhookEventRow>)em.createNativeQuery("""
            update webhook_events set status='Processing',owner=:owner,locked_until=:until,attempts=attempts+1
            where id=:id and status in ('Received','Failed','Processing') and next_attempt_at<=:now
            and (attempts<5 or (status='Processing' and locked_until<=:now))
            and (locked_until is null or locked_until<=:now) returning *""",WebhookEventRow.class).setParameter("owner",owner).setParameter("until",clock.instant().plusSeconds(60)).setParameter("id",id).setParameter("now",clock.instant()).getResultList();
            if(!claimed.isEmpty()){var row=claimed.getFirst();return new Event(row.provider,row.eventType,row.payload,false);}var row=em.find(WebhookEventRow.class,id);return row!=null&&("Processed".equals(row.status)||"Ignored".equals(row.status))?Event.processed():null;});
        if(snapshot==null)return false;if(snapshot.alreadyProcessed())return true;try{var outcome=apply(snapshot);return finish(id,owner,outcome,null);}catch(Exception failure){finish(id,owner,"Failed",failure.getClass().getSimpleName()+": "+failure.getMessage());return false;}}
    private String apply(Event event)throws Exception{
        var root=json.readTree(event.payload());if("payments".equals(event.provider())&&"payment.status_changed".equals(event.type())){var data=root.get("data");if(data==null)return "Ignored";var id=data.get("payment_id");if(id==null)id=data.get("paymentId");
            if(id==null||id.stringValue()==null)return "Ignored";var gateway=payments.getIfAvailable();if(gateway==null)throw new IllegalStateException("Payment provider is disabled");return gateway.verifyAndApply(id.stringValue())?"Processed":"Ignored";}
        if("deliveries".equals(event.provider())&&"delivery.status_changed".equals(event.type())){var id=root.get("delivery_id");if(id==null)id=root.get("deliveryId");
            if(id==null||id.stringValue()==null)return "Ignored";var gateway=deliveries.getIfAvailable();if(gateway==null)throw new IllegalStateException("Delivery provider is disabled");return gateway.verifyAndApply(id.stringValue())?"Processed":"Ignored";}
        return "Ignored";
    }
    private boolean finish(UUID id,UUID owner,String outcome,String error){return Boolean.TRUE.equals(tx.execute(s->{var row=em.find(WebhookEventRow.class,id,LockModeType.PESSIMISTIC_WRITE);if(row==null||!owner.equals(row.owner))return false;
        row.status=outcome;row.owner=null;row.lockedUntil=null;row.lastError=error==null?null:error.substring(0,Math.min(500,error.length()));if("Failed".equals(outcome))row.nextAttemptAt=clock.instant().plusSeconds(Math.min(300,1L<<Math.min(row.attempts,8)));else row.processedAt=clock.instant();return true;}));}
    private record Event(String provider,String type,String payload,boolean alreadyProcessed){static Event processed(){return new Event(null,null,null,true);}}
}
