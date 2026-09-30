package dev.fulfillmenthub.runtime.messaging;

import dev.fulfillmenthub.runtime.outbox.OutboxHandler;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import dev.fulfillmenthub.runtime.payment.PaymentWorkflowService;
import dev.fulfillmenthub.runtime.payment.PaymentGatewayClient;
import dev.fulfillmenthub.runtime.delivery.DeliveryWorkflowService;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

@Service
@ConditionalOnProperty(name="fulfillment.messaging.enabled",havingValue="true")
public class SqsBroker implements OutboxHandler {
    private static final String CONSUMER="domain-events";
    private final SqsClient sqs; private final MessagingSettings settings; private final PaymentWorkflowService payments;private final org.springframework.beans.factory.ObjectProvider<DeliveryWorkflowService> deliveries;private final org.springframework.beans.factory.ObjectProvider<PaymentGatewayClient> paymentGateway;
    private final EntityManager em; private final TransactionTemplate tx; private final Clock clock; private String domainUrl;
    public SqsBroker(SqsClient sqs,MessagingSettings settings,PaymentWorkflowService payments,org.springframework.beans.factory.ObjectProvider<DeliveryWorkflowService> deliveries,org.springframework.beans.factory.ObjectProvider<PaymentGatewayClient> paymentGateway,EntityManager em,TransactionTemplate tx,Clock clock){
        this.sqs=sqs;this.settings=settings;this.payments=payments;this.deliveries=deliveries;this.paymentGateway=paymentGateway;this.em=em;this.tx=tx;this.clock=clock;}
    @PostConstruct public void provision(){
        var dlqUrl=create(settings.domainDlq(),Map.of());var arn=sqs.getQueueAttributes(b->b.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        domainUrl=create(settings.domainQueue(),Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,"60",QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS,"20",
                QueueAttributeName.REDRIVE_POLICY,"{\"deadLetterTargetArn\":\""+arn+"\",\"maxReceiveCount\":\"5\"}"));
        var webhookDlq=create(settings.webhookDlq(),Map.of());var webhookArn=sqs.getQueueAttributes(b->b.queueUrl(webhookDlq).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        create(settings.webhookQueue(),Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,"60",QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS,"20",
                QueueAttributeName.REDRIVE_POLICY,"{\"deadLetterTargetArn\":\""+webhookArn+"\",\"maxReceiveCount\":\"5\"}"));
    }
    private String create(String name,Map<QueueAttributeName,String> attributes){return sqs.createQueue(b->b.queueName(name).attributes(attributes)).queueUrl();}
    @Override public String type(){return "OrderPlaced";}
    @Override public void handle(OutboxRow message){sqs.sendMessage(b->b.queueUrl(domainUrl).messageBody(message.payload).messageAttributes(Map.of(
            "eventType",MessageAttributeValue.builder().dataType("String").stringValue(message.type).build(),
            "messageId",MessageAttributeValue.builder().dataType("String").stringValue(message.id.toString()).build())));}
    public int consume(){var response=sqs.receiveMessage(b->b.queueUrl(domainUrl).maxNumberOfMessages(1).waitTimeSeconds(1).messageAttributeNames("All").messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
        for(var message:response.messages())try{sqs.changeMessageVisibility(b->b.queueUrl(domainUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(300));process(message);sqs.deleteMessage(b->b.queueUrl(domainUrl).receiptHandle(message.receiptHandle()));}
        catch(RuntimeException failure){int receives=Integer.parseInt(message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,"1"));long cap=Math.min(300,5L<<Math.min(Math.max(0,receives-1),6));int delay=(int)Math.max(1,Math.round(cap*(0.5+java.util.concurrent.ThreadLocalRandom.current().nextDouble()*0.5)));
            sqs.changeMessageVisibility(b->b.queueUrl(domainUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(delay));}return response.messages().size();}
    private void process(Message message){var messageId=message.messageAttributes().get("messageId").stringValue();var eventType=message.messageAttributes().get("eventType").stringValue();var owner=claim(CONSUMER+":"+eventType,messageId);if(owner==null)return;
        try{var marker="\"orderId\":\"";var start=message.body().indexOf(marker);if(start<0)throw new IllegalArgumentException("Missing orderId");
            start+=marker.length();var end=message.body().indexOf('"',start);var orderId=java.util.UUID.fromString(message.body().substring(start,end));
            if("OrderPlaced".equals(eventType))payments.createForOrder(orderId);else if("OrderPaid".equals(eventType)){var delivery=deliveries.getIfAvailable();if(delivery==null)throw new IllegalStateException("Delivery provider is disabled");delivery.request(orderId);}
            else if("OrderCancelled".equals(eventType)){
                var gateway=paymentGateway.getIfAvailable();if(gateway==null)throw new IllegalStateException("Payment provider is disabled");gateway.refundForOrder(orderId);
                var delivery=deliveries.getIfAvailable();if(delivery==null)throw new IllegalStateException("Delivery provider is disabled");delivery.cancelForOrder(orderId);
            }
            else if("PaymentPaid".equals(eventType)){var gateway=paymentGateway.getIfAvailable();if(gateway==null)throw new IllegalStateException("Payment provider is disabled");gateway.refundForOrder(orderId);}
            else throw new IllegalArgumentException("Unknown event type: "+eventType);complete(CONSUMER+":"+eventType,messageId,owner);
        }catch(RuntimeException failure){release(CONSUMER+":"+eventType,messageId,owner,failure);throw failure;}}
    @SuppressWarnings("unchecked") private java.util.UUID claim(String consumer,String messageId){var owner=java.util.UUID.randomUUID();return tx.execute(s->{var rows=(java.util.List<java.util.UUID>)em.createNativeQuery("""
            insert into processed_messages(consumer,message_id,status,owner,locked_until,attempts)
            values (:consumer,:message,'Processing',:owner,:until,1)
            on conflict (consumer,message_id) do update set status='Processing',owner=:owner,locked_until=:until,attempts=processed_messages.attempts+1,last_error=null
            where processed_messages.status<>'Processed' and (processed_messages.locked_until is null or processed_messages.locked_until<=:now)
            returning owner""").setParameter("consumer",consumer).setParameter("message",messageId).setParameter("owner",owner).setParameter("now",clock.instant()).setParameter("until",clock.instant().plusSeconds(60)).getResultList();return rows.isEmpty()?null:owner;});}
    private void complete(String consumer,String messageId,java.util.UUID owner){tx.executeWithoutResult(s->em.createNativeQuery("update processed_messages set status='Processed',processed_at=:now,owner=null,locked_until=null where consumer=:consumer and message_id=:message and owner=:owner")
            .setParameter("now",clock.instant()).setParameter("consumer",consumer).setParameter("message",messageId).setParameter("owner",owner).executeUpdate());}
    private void release(String consumer,String messageId,java.util.UUID owner,RuntimeException failure){tx.executeWithoutResult(s->em.createNativeQuery("update processed_messages set status='Pending',owner=null,locked_until=null,last_error=:error where consumer=:consumer and message_id=:message and owner=:owner")
            .setParameter("error",failure.getClass().getSimpleName()).setParameter("consumer",consumer).setParameter("message",messageId).setParameter("owner",owner).executeUpdate());}
}
