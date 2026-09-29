package dev.fulfillmenthub.worker;

import dev.fulfillmenthub.runtime.RuntimeConfiguration;
import dev.fulfillmenthub.runtime.outbox.OutboxProcessor;
import dev.fulfillmenthub.runtime.payment.PaymentWorkflowService;
import dev.fulfillmenthub.runtime.payment.InProcessOrderPlacedHandler;
import dev.fulfillmenthub.runtime.payment.PaymentGatewayClient;
import dev.fulfillmenthub.runtime.webhook.WebhookProcessingService;
import dev.fulfillmenthub.runtime.delivery.DeliveryWorkflowService;
import dev.fulfillmenthub.runtime.messaging.SqsBroker;
import dev.fulfillmenthub.runtime.messaging.SqsConfiguration;
import dev.fulfillmenthub.runtime.messaging.OrderPaidSqsPublisher;
import dev.fulfillmenthub.runtime.messaging.OrderCancelledSqsPublisher;
import dev.fulfillmenthub.runtime.messaging.PaymentPaidSqsPublisher;
import dev.fulfillmenthub.runtime.messaging.WebhookQueue;
import dev.fulfillmenthub.runtime.providers.ProviderResilience;
import dev.fulfillmenthub.runtime.reconciliation.ReconciliationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EntityScan("dev.fulfillmenthub.runtime")
@ConfigurationPropertiesScan("dev.fulfillmenthub.runtime")
@Import({RuntimeConfiguration.class,OutboxProcessor.class,OutboxPoller.class,PaymentWorkflowService.class,
        InProcessOrderPlacedHandler.class,PaymentGatewayClient.class,DeliveryWorkflowService.class,WebhookProcessingService.class,SqsConfiguration.class,SqsBroker.class,WebhookQueue.class,OrderPaidSqsPublisher.class,OrderCancelledSqsPublisher.class,PaymentPaidSqsPublisher.class,ProviderResilience.class,ReconciliationService.class})
public class WorkerApplication {
 public static void main(String[] args){SpringApplication app=new SpringApplication(WorkerApplication.class);app.setAdditionalProfiles("worker");app.run(args);}
}
