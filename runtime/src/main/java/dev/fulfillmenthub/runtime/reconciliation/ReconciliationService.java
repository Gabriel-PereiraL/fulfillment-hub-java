package dev.fulfillmenthub.runtime.reconciliation;

import dev.fulfillmenthub.runtime.delivery.DeliveryWorkflowService;
import dev.fulfillmenthub.runtime.payment.PaymentGatewayClient;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ReconciliationService {
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectProvider<PaymentGatewayClient> payments;
    private final ObjectProvider<DeliveryWorkflowService> deliveries;

    public ReconciliationService(EntityManager em, TransactionTemplate tx,
            ObjectProvider<PaymentGatewayClient> payments, ObjectProvider<DeliveryWorkflowService> deliveries) {
        this.em = em; this.tx = tx; this.payments = payments; this.deliveries = deliveries;
    }

    public int runBatch(int limit) {
        int bounded = Math.max(1, Math.min(limit, 50));
        int processed = 0;
        var payment = payments.getIfAvailable();
        if (payment != null) {
            for (String providerId : paymentIds(bounded)) { try { payment.verifyAndApply(providerId); processed++; } catch (RuntimeException ignored) { } }
            for (UUID orderId : cancelledPaidOrders(bounded)) { try { payment.refundForOrder(orderId); processed++; } catch (RuntimeException ignored) { } }
        }
        var delivery = deliveries.getIfAvailable();
        if (delivery != null) {
            for (UUID orderId : paidOrdersWithoutDelivery(bounded)) { try { delivery.request(orderId); processed++; } catch (RuntimeException ignored) { } }
            for (String providerId : deliveryIds(bounded)) { try { delivery.verifyAndApply(providerId); processed++; } catch (RuntimeException ignored) { } }
        }
        return processed;
    }

    private List<String> paymentIds(int limit) { return tx.execute(s -> em.createQuery(
            "select p.providerPaymentId from PaymentRow p where p.status in ('Pending','Authorized') and p.providerPaymentId is not null order by p.updatedAt", String.class)
            .setMaxResults(limit).getResultList()); }
    private List<UUID> cancelledPaidOrders(int limit) { return tx.execute(s -> em.createQuery(
            "select p.orderId from PaymentRow p, OrderRow o where p.orderId=o.id and p.status='Paid' and o.status='Cancelled' order by p.updatedAt", UUID.class)
            .setMaxResults(limit).getResultList()); }
    private List<UUID> paidOrdersWithoutDelivery(int limit) { return tx.execute(s -> em.createQuery(
            "select o.id from OrderRow o where o.status='Paid' and o.deliveryId is null order by o.updatedAt", UUID.class)
            .setMaxResults(limit).getResultList()); }
    private List<String> deliveryIds(int limit) { return tx.execute(s -> em.createQuery(
            "select d.providerDeliveryId from DeliveryRow d where d.status in ('Requested','InDelivery') and d.providerDeliveryId is not null order by d.updatedAt", String.class)
            .setMaxResults(limit).getResultList()); }
}
