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
    @SuppressWarnings("unchecked") public int drain(int limit){var ids=tx.execute(s->{var claimed=(java.util.List<UUID>)em.createNativeQuery("""
            select id from webhook_events where status in ('Received','Failed') and attempts<5 order by received_at,id
            for update skip locked limit :limit""").setParameter("limit",Math.max(1,Math.min(50,limit))).getResultList();
            if(!claimed.isEmpty())em.createQuery("update WebhookEventRow w set w.status='Processing' where w.id in :ids").setParameter("ids",claimed).executeUpdate();
            return claimed;});
        if(ids==null)return 0;ids.forEach(this::processOne);return ids.size();}
    public void processOne(UUID id){try{var snapshot=tx.execute(s->{var row=em.find(WebhookEventRow.class,id,LockModeType.PESSIMISTIC_WRITE);if(row==null||!("Received".equals(row.status)||"Failed".equals(row.status)||"Processing".equals(row.status))||row.attempts>=5)return null;row.status="Processing";row.attempts++;return new Event(row.provider,row.eventType,row.payload);});
            if(snapshot==null)return;
            var outcome=apply(snapshot);finish(id,outcome,null);}catch(Exception failure){finish(id,"Failed",failure.getClass().getSimpleName()+": "+failure.getMessage());}}
    private String apply(Event event)throws Exception{
        var root=json.readTree(event.payload());if("payments".equals(event.provider())&&"payment.status_changed".equals(event.type())){var data=root.get("data");if(data==null)return "Ignored";var id=data.get("payment_id");if(id==null)id=data.get("paymentId");
            if(id==null||id.stringValue()==null)return "Ignored";var gateway=payments.getIfAvailable();if(gateway==null)throw new IllegalStateException("Payment provider is disabled");return gateway.verifyAndApply(id.stringValue())?"Processed":"Ignored";}
        if("deliveries".equals(event.provider())&&"delivery.status_changed".equals(event.type())){var id=root.get("delivery_id");if(id==null)id=root.get("deliveryId");
            if(id==null||id.stringValue()==null)return "Ignored";var gateway=deliveries.getIfAvailable();if(gateway==null)throw new IllegalStateException("Delivery provider is disabled");return gateway.verifyAndApply(id.stringValue())?"Processed":"Ignored";}
        return "Ignored";
    }
    private void finish(UUID id,String outcome,String error){tx.executeWithoutResult(s->{var row=em.find(WebhookEventRow.class,id,LockModeType.PESSIMISTIC_WRITE);
        row.status=outcome;row.lastError=error==null?null:error.substring(0,Math.min(500,error.length()));if(!"Failed".equals(outcome))row.processedAt=clock.instant();});}
    private record Event(String provider,String type,String payload){}
}
