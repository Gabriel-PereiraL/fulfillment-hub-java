package dev.fulfillmenthub.runtime.payment;

import dev.fulfillmenthub.runtime.orders.OrderRow;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import dev.fulfillmenthub.runtime.providers.ProviderResilience;
import com.fasterxml.jackson.annotation.JsonProperty;

@Service
@ConditionalOnProperty(name="fulfillment.providers.payment.enabled",havingValue="true")
public class PaymentGatewayClient {
    record Money(BigDecimal amount,String currency) {}
    record Create(Money amount,@JsonProperty("order_reference") String orderReference,@JsonProperty("customer_reference") String customerReference) {}
    record Remote(String id,String status,Money amount,@JsonProperty("failure_code") String failureCode,@JsonProperty("updated_at") Instant updatedAt) {}
    record Refund(BigDecimal amount) {}
    record RefundRemote(String id,@JsonProperty("payment_id") String paymentId,String status,BigDecimal amount,@JsonProperty("created_at") Instant createdAt) {}
    private final EntityManager em;private final TransactionTemplate tx;private final TransactionTemplate requiresNew;private final Clock clock;private final RestClient http;private final ProviderResilience resilience;
    public PaymentGatewayClient(EntityManager em,TransactionTemplate tx,Clock clock,PaymentProviderSettings settings,ProviderResilience resilience){
        this.em=em;this.tx=tx;this.clock=clock;this.resilience=resilience;this.requiresNew=new TransactionTemplate(java.util.Objects.requireNonNull(tx.getTransactionManager()));this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);var requests=new SimpleClientHttpRequestFactory();requests.setConnectTimeout(java.time.Duration.ofSeconds(5));requests.setReadTimeout(java.time.Duration.ofSeconds(5));this.http=RestClient.builder().requestFactory(requests).baseUrl(settings.baseUrl().toString())
                .defaultHeader("Authorization","Bearer "+settings.token()).build();}
    public void submit(UUID paymentId){
        var request=requiresNew.execute(s->{var payment=em.find(PaymentRow.class,paymentId,LockModeType.PESSIMISTIC_WRITE);if(payment==null||payment.providerPaymentId!=null)return null;
            var order=em.find(OrderRow.class,payment.orderId);var attempt=payment.attempts.stream().filter(a->a.completedAt==null).findFirst().orElse(null);
            if(attempt==null){attempt=new PaymentAttemptRow();attempt.id=UUID.randomUUID();attempt.payment=payment;
                attempt.attemptNumber=payment.attempts.size()+1;attempt.startedAt=clock.instant();payment.attempts.add(attempt);em.persist(attempt);}
            payment.status="Submitting";payment.updatedAt=clock.instant();em.flush();
            return new Request(payment.id,payment.orderId,payment.providerIdempotencyKey,new Create(new Money(payment.amount,payment.currency),payment.orderId.toString(),order.customerId.toString()));});
        if(request==null)return;
        try{var remote=resilience.execute("payment",()->http.post().uri("/payments/v1/payments").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",request.key())
                .body(request.body()).retrieve().body(Remote.class));if(remote==null)throw new IllegalStateException("Empty payment provider response");apply(request.paymentId(),remote,null);
        }catch(RestClientResponseException failure){if(failure.getStatusCode().is5xxServerError()||failure.getStatusCode().value()==408||failure.getStatusCode().value()==429){markUnknown(request.paymentId(),"provider_http_"+failure.getStatusCode().value());throw failure;}
            apply(request.paymentId(),null,"provider_http_"+failure.getStatusCode().value());}
        catch(RestClientException failure){markUnknown(request.paymentId(),"provider_response_unknown");throw failure;}}
    public boolean resume(UUID paymentId){submit(paymentId);return true;}
    public boolean verifyAndApply(String providerPaymentId){
        var paymentId=tx.execute(s->em.createQuery("select p.id from PaymentRow p where p.providerPaymentId=:id",UUID.class)
                .setParameter("id",providerPaymentId).getResultStream().findFirst().orElse(null));
        if(paymentId==null)return false;
        var remote=resilience.execute("payment",()->http.get().uri("/payments/v1/payments/{id}",providerPaymentId).retrieve().body(Remote.class));
        if(remote==null)throw new IllegalStateException("Empty payment provider response");apply(paymentId,remote,null);return true;
    }
    public boolean refundForOrder(UUID orderId){
        var snapshot=tx.execute(s->{var payment=em.createQuery("select p from PaymentRow p where p.orderId=:order",PaymentRow.class)
                .setParameter("order",orderId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
            if(payment==null||"Refunded".equals(payment.status)||!"Paid".equals(payment.status)||payment.providerPaymentId==null)return null;
            return new RefundRequest(payment.id,payment.providerPaymentId,payment.amount,"refund-"+payment.id.toString().replace("-",""));});
        if(snapshot==null)return false;
        var remote=resilience.execute("payment",()->http.post().uri("/payments/v1/payments/{id}/refunds",snapshot.providerId())
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",snapshot.key()).body(new Refund(snapshot.amount())).retrieve().body(RefundRemote.class));
        if(remote==null||!"succeeded".equals(remote.status()))throw new IllegalStateException("Payment refund was not accepted");
        tx.executeWithoutResult(s->{var payment=em.find(PaymentRow.class,snapshot.paymentId(),LockModeType.PESSIMISTIC_WRITE);
            payment.status="Refunded";payment.lastProviderEventAt=remote.createdAt();payment.updatedAt=clock.instant();});return true;
    }
    private void apply(UUID paymentId,Remote remote,String failure){tx.executeWithoutResult(s->{var payment=em.find(PaymentRow.class,paymentId,LockModeType.PESSIMISTIC_WRITE);var previousPaymentStatus=payment.status;var now=clock.instant();
        var attempt=payment.attempts.stream().filter(a->a.completedAt==null).findFirst().orElse(null);
        if(attempt!=null)attempt.completedAt=now;
        if(remote==null){if(attempt!=null){attempt.outcome="PermanentFailure";attempt.errorCode=failure;}payment.status="Failed";payment.failureReason=failure;}else{
            if(attempt!=null){attempt.outcome="Succeeded";attempt.providerReference=remote.id();}payment.providerPaymentId=remote.id();payment.status=map(remote.status());payment.failureReason=remote.failureCode();payment.lastProviderEventAt=remote.updatedAt();}
        payment.updatedAt=now;var order=em.find(OrderRow.class,payment.orderId,LockModeType.PESSIMISTIC_WRITE);
        if("Paid".equals(payment.status)&&("Created".equals(order.status)||"AwaitingPayment".equals(order.status))){var from=order.status;order.status="Paid";order.updatedAt=now;history(order.id,from,"Paid",now,"Payment settled");em.persist(OutboxRow.pending("OrderPaid",order.id,now));}
        else if("Paid".equals(payment.status)&&!"Paid".equals(previousPaymentStatus)&&"Cancelled".equals(order.status)){em.persist(OutboxRow.pending("PaymentPaid",order.id,now));}
        if("Failed".equals(payment.status)&&("Created".equals(order.status)||"AwaitingPayment".equals(order.status))){var from=order.status;order.status="Cancelled";order.cancellationReason="PaymentFailed";order.updatedAt=now;
            em.createNativeQuery("update products p set stock_quantity=p.stock_quantity+i.quantity,version=p.version+1 from order_items i where i.order_id=:order and p.id=i.product_id")
                    .setParameter("order",order.id).executeUpdate();history(order.id,from,"Cancelled",now,failure==null?payment.failureReason:failure);}
    });}
    private void markUnknown(UUID paymentId,String reason){requiresNew.executeWithoutResult(s->{var payment=em.find(PaymentRow.class,paymentId,LockModeType.PESSIMISTIC_WRITE);if(payment==null||payment.providerPaymentId!=null)return;
        var attempt=payment.attempts.stream().filter(a->a.completedAt==null).findFirst().orElse(null);if(attempt!=null){attempt.completedAt=clock.instant();attempt.outcome="Unknown";attempt.errorCode=reason;}
        payment.status="Unknown";payment.failureReason=reason;payment.updatedAt=clock.instant();});}
    private void history(UUID order,String from,String to,Instant at,String reason){em.createNativeQuery("insert into order_status_changes(id,order_id,from_status,to_status,occurred_at,reason) values (:id,:order,:from,:to,:at,:reason)")
            .setParameter("id",UUID.randomUUID()).setParameter("order",order).setParameter("from",from).setParameter("to",to).setParameter("at",at).setParameter("reason",reason).executeUpdate();}
    private static String map(String value){return switch(value){case "paid"->"Paid";case "failed"->"Failed";case "authorized"->"Authorized";case "refunded"->"Refunded";default->"Pending";};}
    private record Request(UUID paymentId,UUID orderId,String key,Create body){}
    private record RefundRequest(UUID paymentId,String providerId,BigDecimal amount,String key){}
}
