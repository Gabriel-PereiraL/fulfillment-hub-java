package dev.fulfillmenthub.runtime.payment;

import dev.fulfillmenthub.runtime.orders.OrderRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

/** Creates the durable local payment before any provider call can occur. */
@Service
public class PaymentWorkflowService {
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final ObjectProvider<PaymentGatewayClient> gateway;

    public PaymentWorkflowService(EntityManager em, TransactionTemplate tx, Clock clock, ObjectProvider<PaymentGatewayClient> gateway) {
        this.em = em; this.tx = tx; this.clock = clock; this.gateway = gateway;
        this.requiresNew = new TransactionTemplate(java.util.Objects.requireNonNull(tx.getTransactionManager()));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID createForOrder(UUID orderId) {
        var paymentId = requiresNew.execute(status -> {
            var order = em.find(OrderRow.class, orderId, LockModeType.PESSIMISTIC_WRITE);
            if (order == null || !"Created".equals(order.status)) return null;
            var existing = em.createQuery("select p from PaymentRow p where p.orderId=:order and p.status not in ('Failed','Cancelled')", PaymentRow.class)
                    .setParameter("order", orderId).getResultStream().findFirst().orElse(null);
            if (existing != null) return existing.id;

            var now = clock.instant();
            var payment = new PaymentRow();
            payment.id = UUID.randomUUID();
            payment.orderId = orderId;
            payment.status = "Pending";
            payment.provider = "simulator";
            payment.providerIdempotencyKey = "order-" + orderId.toString().replace("-", "");
            payment.amount = order.total;
            payment.currency = order.totalCurrency;
            payment.createdAt = now;
            payment.updatedAt = now;
            em.persist(payment);
            order.paymentId = payment.id;
            order.status = "AwaitingPayment";
            order.updatedAt = now;
            em.createNativeQuery("insert into order_status_changes(id,order_id,from_status,to_status,occurred_at,reason) values (:id,:order,'Created','AwaitingPayment',:now,'Payment created')")
                    .setParameter("id", UUID.randomUUID()).setParameter("order", orderId).setParameter("now", now).executeUpdate();
            em.flush();
            return payment.id;
        });
        var client=gateway.getIfAvailable();if(paymentId!=null&&client!=null)client.submit(paymentId);return paymentId;
    }
}
