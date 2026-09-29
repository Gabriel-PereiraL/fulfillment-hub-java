package dev.fulfillmenthub.runtime.messaging;

import dev.fulfillmenthub.runtime.outbox.OutboxHandler;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="fulfillment.messaging.enabled",havingValue="true")
public final class PaymentPaidSqsPublisher implements OutboxHandler {
    private final SqsBroker broker;
    public PaymentPaidSqsPublisher(SqsBroker broker) { this.broker = broker; }
    @Override public String type() { return "PaymentPaid"; }
    @Override public void handle(OutboxRow message) { broker.handle(message); }
}
