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
    private static final String CONSUMER="payment-order-placed";
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
    public int consume(){var response=sqs.receiveMessage(b->b.queueUrl(domainUrl).maxNumberOfMessages(10).waitTimeSeconds(1).messageAttributeNames("All").messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
        for(var message:response.messages())try{process(message);sqs.deleteMessage(b->b.queueUrl(domainUrl).receiptHandle(message.receiptHandle()));}
        catch(RuntimeException failure){int receives=Integer.parseInt(message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,"1"));long cap=Math.min(300,5L<<Math.min(Math.max(0,receives-1),6));int delay=(int)Math.max(1,Math.round(cap*(0.5+java.util.concurrent.ThreadLocalRandom.current().nextDouble()*0.5)));
            sqs.changeMessageVisibility(b->b.queueUrl(domainUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(delay));}return response.messages().size();}
    private void process(Message message){var messageId=message.messageAttributes().get("messageId").stringValue();
        tx.executeWithoutResult(s->{var key=new ProcessedMessageRow.Key(CONSUMER,messageId);if(em.find(ProcessedMessageRow.class,key)!=null)return;
            var marker="\"orderId\":\"";var start=message.body().indexOf(marker);if(start<0)throw new IllegalArgumentException("Missing orderId");
            start+=marker.length();var end=message.body().indexOf('"',start);var orderId=java.util.UUID.fromString(message.body().substring(start,end));var eventType=message.messageAttributes().get("eventType").stringValue();
            if("OrderPlaced".equals(eventType))payments.createForOrder(orderId);else if("OrderPaid".equals(eventType)){var delivery=deliveries.getIfAvailable();if(delivery==null)throw new IllegalStateException("Delivery provider is disabled");delivery.request(orderId);}
            else if("OrderCancelled".equals(eventType)){
                var gateway=paymentGateway.getIfAvailable();if(gateway==null)throw new IllegalStateException("Payment provider is disabled");gateway.refundForOrder(orderId);
                var delivery=deliveries.getIfAvailable();if(delivery==null)throw new IllegalStateException("Delivery provider is disabled");delivery.cancelForOrder(orderId);
            }
            else if("PaymentPaid".equals(eventType)){var gateway=paymentGateway.getIfAvailable();if(gateway==null)throw new IllegalStateException("Payment provider is disabled");gateway.refundForOrder(orderId);}
            else throw new IllegalArgumentException("Unknown event type: "+eventType);
            em.persist(ProcessedMessageRow.create(CONSUMER,messageId,clock.instant()));});}
}
