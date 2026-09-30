package dev.fulfillmenthub.runtime.delivery;

import dev.fulfillmenthub.runtime.orders.OrderRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import dev.fulfillmenthub.runtime.providers.ProviderResilience;
import dev.fulfillmenthub.runtime.providers.ProviderStatePolicy;
import com.fasterxml.jackson.annotation.JsonProperty;

@Service
@ConditionalOnProperty(name="fulfillment.providers.delivery.enabled",havingValue="true")
public class DeliveryWorkflowService {
    record Token(@JsonProperty("access_token") String accessToken,@JsonProperty("token_type") String tokenType,@JsonProperty("expires_in") long expiresIn,String scope){}
    record Money(BigDecimal amount,String currency){}
    record Address(@JsonProperty("street_address") String streetAddress,String city,String state,@JsonProperty("postal_code") String postalCode,String country){}
    record Party(String name,Address address,String phone){}
    record QuoteRequest(Party pickup,Party dropoff,@JsonProperty("manifest_total_value") Money manifestTotalValue){}
    record Quote(String id,Money fee,@JsonProperty("expires_at") Instant expiresAt,@JsonProperty("estimated_dropoff_at") Instant estimatedDropoffAt,@JsonProperty("duration_minutes") int durationMinutes,@JsonProperty("pickup_duration_minutes") int pickupDurationMinutes){}
    record Create(@JsonProperty("quote_id") String quoteId,@JsonProperty("external_id") String externalId){}
    record Remote(String id,String status,Money fee,@JsonProperty("tracking_url") String trackingUrl,@JsonProperty("updated_at") Instant updatedAt){}
    private final EntityManager em;private final TransactionTemplate tx;private final TransactionTemplate requiresNew;private final Clock clock;private final RestClient http;private final DeliveryProviderSettings settings;private final ProviderResilience resilience;
    public DeliveryWorkflowService(EntityManager em,TransactionTemplate tx,Clock clock,DeliveryProviderSettings settings,ProviderResilience resilience){this.em=em;this.tx=tx;this.clock=clock;this.settings=settings;this.resilience=resilience;
        this.requiresNew=new TransactionTemplate(java.util.Objects.requireNonNull(tx.getTransactionManager()));this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);var requests=new SimpleClientHttpRequestFactory();requests.setConnectTimeout(java.time.Duration.ofSeconds(5));requests.setReadTimeout(java.time.Duration.ofSeconds(5));this.http=RestClient.builder().requestFactory(requests).baseUrl(settings.baseUrl().toString()).build();}
    public UUID request(UUID orderId){var snapshot=requiresNew.execute(s->{var order=em.find(OrderRow.class,orderId,LockModeType.PESSIMISTIC_WRITE);if(order==null||!"Paid".equals(order.status))return null;
        var active=em.createQuery("select d from DeliveryRow d where d.orderId=:order and d.status not in ('Cancelled','Delivered','Returned','FailedPermanent')",DeliveryRow.class)
                .setParameter("order",orderId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
        var existingQuote=active==null?null:em.find(DeliveryQuoteRow.class,active.quoteId);return new Snapshot(order,active,toQuote(existingQuote));});if(snapshot==null)return null;
        if(snapshot.delivery()!=null&&snapshot.delivery().providerDeliveryId!=null)return snapshot.delivery().id;
        String token;Quote quote=snapshot.quote();try{token=token();if(quote==null||!quote.expiresAt().isAfter(clock.instant()))quote=quote(snapshot.order(),token);}
        catch(RestClientResponseException failure){if(permanent(failure)){rejectOrderPermanent(orderId,"delivery_setup_http_"+failure.getStatusCode().value());return null;}throw failure;}var selectedQuote=quote;
        var deliveryId=requiresNew.execute(s->{var order=em.find(OrderRow.class,orderId,LockModeType.PESSIMISTIC_WRITE);var active=em.createQuery("select d from DeliveryRow d where d.orderId=:order and d.status not in ('Cancelled','Delivered','Returned','FailedPermanent')",DeliveryRow.class)
                .setParameter("order",orderId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);if(active!=null&&active.providerDeliveryId!=null)return active.id;
            var currentQuote=active==null?null:em.find(DeliveryQuoteRow.class,active.quoteId);if(currentQuote==null||!currentQuote.expiresAt.isAfter(clock.instant()))currentQuote=persistQuote(orderId,selectedQuote);
            if(active!=null){active.quoteId=currentQuote.id;active.fee=currentQuote.fee;active.currency=currentQuote.currency;active.updatedAt=clock.instant();return active.id;}
            var d=new DeliveryRow();d.id=UUID.randomUUID();d.orderId=orderId;d.quoteId=currentQuote.id;d.provider="simulator";d.providerIdempotencyKey="order-"+orderId.toString().replace("-","")+"-delivery-1";
            d.status="Pending";d.fee=currentQuote.fee;d.currency=currentQuote.currency;d.createdAt=clock.instant();d.updatedAt=d.createdAt;em.persist(d);em.flush();return d.id;});
        var local=tx.execute(s->em.find(DeliveryRow.class,deliveryId));if(local.providerDeliveryId!=null)return deliveryId;var localQuote=tx.execute(s->em.find(DeliveryQuoteRow.class,local.quoteId));
        Remote remote;try{remote=resilience.execute("delivery",()->http.post().uri("/delivery/v1/customers/{customer}/deliveries",settings.customerId()).header("Authorization","Bearer "+token)
                .header("Idempotency-Key",local.providerIdempotencyKey).contentType(MediaType.APPLICATION_JSON).body(new Create(localQuote.providerQuoteId,"FH-"+orderId)).retrieve()
                .onStatus(status->status.value()==409,(request,response)->{}).body(Remote.class));}catch(RestClientResponseException failure){int code=failure.getStatusCode().value();if(code<500&&code!=408&&code!=429){rejectPermanent(deliveryId,orderId,"provider_http_"+code);return deliveryId;}throw failure;}
        if(remote==null)throw new IllegalStateException("Empty delivery provider response");tx.executeWithoutResult(s->{var d=em.find(DeliveryRow.class,deliveryId,LockModeType.PESSIMISTIC_WRITE);
            d.providerDeliveryId=remote.id();d.status=map(remote.status());d.trackingUrl=remote.trackingUrl();d.fee=remote.fee().amount();d.currency=remote.fee().currency();d.updatedAt=clock.instant();
            var order=em.find(OrderRow.class,orderId,LockModeType.PESSIMISTIC_WRITE);order.deliveryId=d.id;order.updatedAt=clock.instant();
            if("Cancelled".equals(order.status)){em.persist(dev.fulfillmenthub.runtime.outbox.OutboxRow.pending("OrderCancelled",order.id,order.updatedAt));return;}
            if("Paid".equals(order.status)){order.status="DeliveryRequested";history(order.id,"Paid","DeliveryRequested",order.updatedAt,"Delivery created");}});return deliveryId;}
    public boolean verifyAndApply(String providerDeliveryId){var localId=tx.execute(s->em.createQuery("select d.id from DeliveryRow d where d.providerDeliveryId=:id",UUID.class).setParameter("id",providerDeliveryId).getResultStream().findFirst().orElse(null));
        if(localId==null)return false;var token=token();var remote=resilience.execute("delivery",()->http.get().uri("/delivery/v1/customers/{customer}/deliveries/{id}",settings.customerId(),providerDeliveryId)
                .header("Authorization","Bearer "+token).retrieve().body(Remote.class));if(remote==null)throw new IllegalStateException("Empty delivery provider response");
        tx.executeWithoutResult(s->{var delivery=em.find(DeliveryRow.class,localId,LockModeType.PESSIMISTIC_WRITE);var now=clock.instant();var eventId="reconcile-"+remote.updatedAt();if(em.createQuery("select count(e) from DeliveryEventRow e where e.delivery.id=:delivery and e.providerEventId=:event",Long.class).setParameter("delivery",localId).setParameter("event",eventId).getSingleResult()>0)return;
            var reported=map(remote.status());var decision=ProviderStatePolicy.delivery(delivery.status,reported,delivery.lastProviderEventAt,remote.updatedAt());if(decision==ProviderStatePolicy.Decision.Applied){delivery.status=reported;delivery.lastProviderEventAt=remote.updatedAt();}delivery.updatedAt=now;
            var event=new DeliveryEventRow();event.id=UUID.randomUUID();event.delivery=delivery;event.providerEventId=eventId;event.providerStatus=remote.status();event.occurredAt=remote.updatedAt();event.receivedAt=now;event.disposition=decision.name();delivery.events.add(event);em.persist(event);
            var order=em.find(OrderRow.class,delivery.orderId,LockModeType.PESSIMISTIC_WRITE);
            if("Delivered".equals(delivery.status)&&("Paid".equals(order.status)||"DeliveryRequested".equals(order.status))){var from=order.status;order.status="Delivered";order.updatedAt=now;history(order.id,from,"Delivered",now,"Delivery completed");}
            else if(("Returned".equals(delivery.status)||"Cancelled".equals(delivery.status))&&!"Cancelled".equals(order.status)){cancelAndCompensate(order,now,"Delivery"+delivery.status,delivery.id);}});return true;}
    public void cancelForOrder(UUID orderId){var remoteId=tx.execute(s->em.createQuery("select d.providerDeliveryId from DeliveryRow d where d.orderId=:order and d.status not in ('Cancelled','Delivered','Returned')",String.class)
            .setParameter("order",orderId).getResultStream().findFirst().orElse(null));if(remoteId==null)return;var token=token();
        var remote=resilience.execute("delivery",()->http.post().uri("/delivery/v1/customers/{customer}/deliveries/{id}/cancel",settings.customerId(),remoteId).header("Authorization","Bearer "+token).retrieve().body(Remote.class));
        if(remote==null)throw new IllegalStateException("Empty delivery cancellation response");tx.executeWithoutResult(s->{var delivery=em.createQuery("select d from DeliveryRow d where d.providerDeliveryId=:id",DeliveryRow.class)
                .setParameter("id",remoteId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();delivery.status="Cancelled";delivery.updatedAt=clock.instant();});}
    private String token(){var form=new LinkedMultiValueMap<String,String>();form.setAll(Map.of("client_id",settings.clientId(),"client_secret",settings.clientSecret(),"grant_type","client_credentials"));
        var result=resilience.execute("delivery",()->http.post().uri("/delivery/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Token.class));if(result==null)throw new IllegalStateException("Empty delivery token response");return result.accessToken();}
    private Quote quote(OrderRow order,String token){var pickup=new Party("Fulfillment Hub",new Address("Warehouse 1","Sao Paulo","SP","01001000","BR"),"+5511000000000");
        var dropoff=new Party("Customer",new Address(order.street,order.city,order.state,order.postalCode,order.country),"+5511999999999");
        var value=resilience.execute("delivery",()->http.post().uri("/delivery/v1/customers/{customer}/delivery_quotes",settings.customerId()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .body(new QuoteRequest(pickup,dropoff,new Money(order.subtotal,order.subtotalCurrency))).retrieve().body(Quote.class));if(value==null)throw new IllegalStateException("Empty delivery quote response");return value;}
    private DeliveryQuoteRow persistQuote(UUID orderId,Quote quote){var q=new DeliveryQuoteRow();q.id=UUID.randomUUID();q.orderId=orderId;q.provider="simulator";q.providerQuoteId=quote.id();q.fee=quote.fee().amount();q.currency=quote.fee().currency();
        q.expiresAt=quote.expiresAt();q.estimatedDropoffAt=quote.estimatedDropoffAt();q.durationMinutes=quote.durationMinutes();q.pickupDurationMinutes=quote.pickupDurationMinutes();q.createdAt=clock.instant();em.persist(q);return q;}
    private static Quote toQuote(DeliveryQuoteRow row){return row==null?null:new Quote(row.providerQuoteId,new Money(row.fee,row.currency),row.expiresAt,row.estimatedDropoffAt,row.durationMinutes,row.pickupDurationMinutes);}
    private void rejectPermanent(UUID deliveryId,UUID orderId,String reason){requiresNew.executeWithoutResult(s->{var delivery=em.find(DeliveryRow.class,deliveryId,LockModeType.PESSIMISTIC_WRITE);delivery.status="FailedPermanent";delivery.updatedAt=clock.instant();var order=em.find(OrderRow.class,orderId,LockModeType.PESSIMISTIC_WRITE);cancelAndCompensate(order,delivery.updatedAt,reason,delivery.id);});}
    private void rejectOrderPermanent(UUID orderId,String reason){requiresNew.executeWithoutResult(s->{var order=em.find(OrderRow.class,orderId,LockModeType.PESSIMISTIC_WRITE);if(order!=null)cancelAndCompensate(order,clock.instant(),reason,null);});}
    private void cancelAndCompensate(OrderRow order,Instant now,String reason,UUID deliveryId){if("Cancelled".equals(order.status))return;var from=order.status;if(deliveryId!=null)order.deliveryId=deliveryId;
        em.createNativeQuery("update products p set stock_quantity=p.stock_quantity+i.quantity,version=p.version+1 from order_items i where i.order_id=:order and p.id=i.product_id")
                .setParameter("order",order.id).executeUpdate();order.status="Cancelled";order.cancellationReason="DeliveryRejected";order.updatedAt=now;history(order.id,from,"Cancelled",now,reason);
        em.persist(dev.fulfillmenthub.runtime.outbox.OutboxRow.pending("OrderCancelled",order.id,now));}
    private static boolean permanent(RestClientResponseException failure){int code=failure.getStatusCode().value();return code<500&&code!=408&&code!=429;}
    private static String map(String status){return switch(status){case "delivered"->"Delivered";case "returned"->"Returned";case "cancelled"->"Cancelled";default->"Requested";};}
    private void history(UUID order,String from,String to,Instant at,String reason){em.createNativeQuery("insert into order_status_changes(id,order_id,from_status,to_status,occurred_at,reason) values (:id,:order,:from,:to,:at,:reason)")
            .setParameter("id",UUID.randomUUID()).setParameter("order",order).setParameter("from",from).setParameter("to",to).setParameter("at",at).setParameter("reason",reason).executeUpdate();}
    private record Snapshot(OrderRow order,DeliveryRow delivery,Quote quote){}
}
