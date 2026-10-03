package dev.fulfillmenthub.runtime.messaging;

import dev.fulfillmenthub.runtime.webhook.WebhookProcessingService;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@Service
@ConditionalOnProperty(name="fulfillment.messaging.enabled",havingValue="true")
public final class WebhookQueue {
    private final SqsClient sqs;
    private final MessagingSettings settings;
    private String queueUrl;

    public WebhookQueue(SqsClient sqs, MessagingSettings settings) { this.sqs=sqs; this.settings=settings; }

    @PostConstruct void provision() {
        if (!settings.provision()) {
            queueUrl=sqs.getQueueUrl(b->b.queueName(settings.webhookQueue())).queueUrl();
            return;
        }
        var dlq=sqs.createQueue(b->b.queueName(settings.webhookDlq())).queueUrl();
        var arn=sqs.getQueueAttributes(b->b.queueUrl(dlq).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        queueUrl=sqs.createQueue(b->b.queueName(settings.webhookQueue()).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT,"60",QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS,"20",
                QueueAttributeName.REDRIVE_POLICY,"{\"deadLetterTargetArn\":\""+arn+"\",\"maxReceiveCount\":\"5\"}"))).queueUrl();
    }

    public void publish(UUID webhookId) {
        sqs.sendMessage(b->b.queueUrl(queueUrl).messageBody(webhookId.toString()).messageAttributes(Map.of(
                "messageType", MessageAttributeValue.builder().dataType("String").stringValue("WebhookStored").build())));
    }

    public int consume(WebhookProcessingService processor) {
        var response=sqs.receiveMessage(b->b.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(1)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
        for(var message:response.messages())try{sqs.changeMessageVisibility(b->b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(300));if(!processor.processOne(UUID.fromString(message.body())))throw new IllegalStateException("Webhook was not completed");
            sqs.deleteMessage(b->b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));}
        catch(RuntimeException failure){int receives=Integer.parseInt(message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,"1"));
            int delay=(int)Math.min(300,Math.max(1,5L<<Math.min(Math.max(0,receives-1),6)));
            sqs.changeMessageVisibility(b->b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(delay));}
        return response.messages().size();
    }
}
