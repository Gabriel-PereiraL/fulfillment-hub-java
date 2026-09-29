package dev.fulfillmenthub.runtime.payment;

import dev.fulfillmenthub.runtime.outbox.OutboxHandler;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="fulfillment.messaging.enabled",havingValue="false",matchIfMissing=true)
public class InProcessOrderPlacedHandler implements OutboxHandler {
    private final PaymentWorkflowService payments;
    public InProcessOrderPlacedHandler(PaymentWorkflowService payments) { this.payments = payments; }
    @Override public String type() { return "OrderPlaced"; }
    @Override public void handle(OutboxRow message) { payments.createForOrder(message.aggregateId); }
}
