package dev.fulfillmenthub.simulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@RestController
public class SimulatorController {
    record Amount(BigDecimal amount, String currency) {}
    record PaymentRequest(Amount amount, String orderReference, String customerReference) {}
    record PaymentView(String id, String status, Amount amount, String failureCode, Instant updatedAt) {}
    record RefundRequest(BigDecimal amount) {}
    record RefundView(String id, String paymentId, String status, BigDecimal amount, Instant createdAt) {}
    record TokenView(String accessToken, String tokenType, long expiresIn, String scope) {}
    record QuoteRequest(Party pickup, Party dropoff, Amount manifestTotalValue) {}
    record Party(String name, Address address, String phone) {}
    record Address(String streetAddress, String city, String state, String postalCode, String country) {}
    record QuoteView(String id, Amount fee, Instant expiresAt, Instant estimatedDropoffAt, int durationMinutes, int pickupDurationMinutes) {}
    record DeliveryRequest(String quoteId, String externalId) {}
    record DeliveryView(String id, String status, Amount fee, String trackingUrl, Instant updatedAt) {}

    private final Map<String, PaymentView> payments = new ConcurrentHashMap<>();
    private final Map<String, String> paymentKeys = new ConcurrentHashMap<>();
    private final Map<String, QuoteView> quotes = new ConcurrentHashMap<>();
    private final Map<String, DeliveryView> deliveries = new ConcurrentHashMap<>();
    private final Map<String, String> deliveryKeys = new ConcurrentHashMap<>();
    private final String paymentToken;
    private final String deliveryClientId;
    private final String deliveryClientSecret;
    private final String paymentWebhookUrl;
    private final String paymentWebhookKey;
    private final String deliveryWebhookUrl;
    private final String deliveryWebhookKey;
    private final ObjectMapper json;
    private final Set<String> notified = ConcurrentHashMap.newKeySet();
    private final Set<String> deliveryNotified = ConcurrentHashMap.newKeySet();

    SimulatorController(@Value("${simulator.payment-token}") String paymentToken,
                        @Value("${simulator.delivery-client-id}") String deliveryClientId,
                        @Value("${simulator.delivery-client-secret}") String deliveryClientSecret,
                        @Value("${simulator.payment-webhook-url:}") String paymentWebhookUrl,
                        @Value("${simulator.payment-webhook-key:}") String paymentWebhookKey,
                        @Value("${simulator.delivery-webhook-url:}") String deliveryWebhookUrl,
                        @Value("${simulator.delivery-webhook-key:}") String deliveryWebhookKey,ObjectMapper json) {
        this.paymentToken = paymentToken; this.deliveryClientId = deliveryClientId; this.deliveryClientSecret = deliveryClientSecret;
        this.paymentWebhookUrl=paymentWebhookUrl;this.paymentWebhookKey=paymentWebhookKey;this.deliveryWebhookUrl=deliveryWebhookUrl;this.deliveryWebhookKey=deliveryWebhookKey;this.json=json;
    }

    @GetMapping("/health") Map<String,String> health() { return Map.of("status", "Healthy"); }

    @PostMapping("/payments/v1/payments")
    ResponseEntity<PaymentView> createPayment(@RequestHeader(name="Authorization",required=false) String authorization,
                                               @RequestHeader(name="Idempotency-Key") String key,
                                               @RequestBody PaymentRequest request) {
        if (!authorized(authorization, paymentToken)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var created = new AtomicBoolean();
        var id = paymentKeys.computeIfAbsent(key, ignored -> {
            var cents = request.amount().amount().movePointRight(2).longValueExact();
            var status = cents % 100 == 99 ? "failed" : cents % 100 == 98 ? "paid" : "pending";
            var newId = "pay_" + compactId();
            payments.put(newId, new PaymentView(newId, status, request.amount(), "failed".equals(status) ? "card_declined" : null, Instant.now()));
            created.set(true);
            return newId;
        });
        var payment = payments.get(id);
        return created.get() ? ResponseEntity.created(java.net.URI.create("/payments/v1/payments/" + id)).body(payment) : ResponseEntity.ok(payment);
    }

    @GetMapping("/payments/v1/payments/{id}")
    ResponseEntity<PaymentView> getPayment(@RequestHeader(name="Authorization",required=false) String authorization,@PathVariable("id") String id) {
        if (!authorized(authorization, paymentToken)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ofNullable(payments.get(id));
    }

    @PostMapping("/payments/v1/payments/{id}/refunds")
    ResponseEntity<RefundView> refund(@RequestHeader(name="Authorization",required=false) String authorization,@PathVariable("id") String id,
                                      @RequestBody(required=false) RefundRequest request) {
        if (!authorized(authorization, paymentToken)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var payment = payments.get(id); if (payment == null) return ResponseEntity.notFound().build();
        if (!("paid".equals(payment.status()) || "refunded".equals(payment.status()))) return ResponseEntity.status(422).build();
        var amount = request == null || request.amount() == null ? payment.amount().amount() : request.amount();
        var now = Instant.now(); payments.put(id, new PaymentView(id,"refunded",payment.amount(),null,now));
        return ResponseEntity.ok(new RefundView("ref_"+compactId(),id,"succeeded",amount,now));
    }

    @PostMapping(value="/delivery/oauth/token",consumes="application/x-www-form-urlencoded")
    ResponseEntity<TokenView> token(@RequestBody MultiValueMap<String,String> form) {
        if (!deliveryClientId.equals(form.getFirst("client_id")) || !deliveryClientSecret.equals(form.getFirst("client_secret"))
                || !"client_credentials".equals(form.getFirst("grant_type"))) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(new TokenView("sim_"+compactId(),"Bearer",300,"eats.deliveries"));
    }

    @PostMapping("/delivery/v1/customers/{customerId}/delivery_quotes")
    ResponseEntity<QuoteView> quote(@RequestHeader(name="Authorization",required=false) String authorization,@PathVariable("customerId") String customerId,
                                    @RequestBody QuoteRequest request) {
        if (!bearer(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (request.dropoff()!=null && request.dropoff().address()!=null && request.dropoff().address().postalCode()!=null
                && request.dropoff().address().postalCode().startsWith("00000")) return ResponseEntity.status(422).build();
        var now=Instant.now();var quote=new QuoteView("dqt_"+compactId(),new Amount(new BigDecimal("15.00"),"BRL"),now.plusSeconds(300),now.plusSeconds(1800),30,10);
        quotes.put(quote.id(),quote);return ResponseEntity.ok(quote);
    }

    @PostMapping("/delivery/v1/customers/{customerId}/deliveries")
    ResponseEntity<DeliveryView> createDelivery(@RequestHeader(name="Authorization",required=false)String authorization,
            @RequestHeader(name="Idempotency-Key")String key,@PathVariable("customerId") String customerId,@RequestBody DeliveryRequest request){
        if(!bearer(authorization))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var quote=quotes.get(request.quoteId());if(quote==null||quote.expiresAt().isBefore(Instant.now()))return ResponseEntity.status(422).build();
        var created=new AtomicBoolean();var id=deliveryKeys.computeIfAbsent(key,ignored->{var newId="del_"+compactId();
            deliveries.put(newId,new DeliveryView(newId,"pending",quote.fee(),"http://localhost/track/"+newId,Instant.now()));created.set(true);return newId;});
        var delivery=deliveries.get(id);return created.get()?ResponseEntity.status(HttpStatus.CREATED).body(delivery):
                ResponseEntity.status(HttpStatus.CONFLICT).header("X-Existing-Delivery-Id",id).body(delivery);}

    @GetMapping("/delivery/v1/customers/{customerId}/deliveries/{id}")
    ResponseEntity<DeliveryView> getDelivery(@RequestHeader(name="Authorization",required=false)String authorization,@PathVariable("customerId") String customerId,@PathVariable("id") String id){
        if(!bearer(authorization))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();return ResponseEntity.ofNullable(deliveries.get(id));}

    @PostMapping("/delivery/v1/customers/{customerId}/deliveries/{id}/cancel")
    ResponseEntity<DeliveryView> cancelDelivery(@RequestHeader(name="Authorization",required=false)String authorization,@PathVariable("customerId") String customerId,@PathVariable("id") String id){
        if(!bearer(authorization))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();var delivery=deliveries.get(id);if(delivery==null)return ResponseEntity.notFound().build();
        var cancelled=new DeliveryView(id,"cancelled",delivery.fee(),delivery.trackingUrl(),Instant.now());deliveries.put(id,cancelled);return ResponseEntity.ok(cancelled);}

    private static boolean authorized(String value,String token){return ("Bearer "+token).equals(value);}
    private static boolean bearer(String value){return value!=null&&value.startsWith("Bearer sim_");}
    private static String compactId(){return UUID.randomUUID().toString().replace("-","").substring(0,20);}
    @Scheduled(fixedDelay=1000)
    void settlePayments(){if(paymentWebhookUrl.isBlank())return;for(var entry:payments.entrySet()){var payment=entry.getValue();
        if("pending".equals(payment.status())&&payment.updatedAt().plusSeconds(1).isBefore(Instant.now())){payment=new PaymentView(payment.id(),"paid",payment.amount(),null,Instant.now());payments.put(entry.getKey(),payment);}
        if("paid".equals(payment.status())&&!notified.contains(payment.id()))try{var timestamp=Long.toString(Instant.now().getEpochSecond());var eventId="evt_"+payment.id();
            var payload=json.writeValueAsBytes(Map.of("id",eventId,"type","payment.status_changed","created_at",Instant.now().toString(),"data",Map.of("payment_id",payment.id(),"status","paid","occurred_at",payment.updatedAt().toString())));
            RestClient.create().post().uri(paymentWebhookUrl).contentType(MediaType.APPLICATION_JSON).header("X-Timestamp",timestamp).header("X-Signature",hmac(paymentWebhookKey,timestamp,payload)).body(payload).retrieve().toBodilessEntity();notified.add(payment.id());
        }catch(RuntimeException ignored){/* retry on the next tick */}}}
    @Scheduled(fixedDelay=1000)
    void advanceDeliveries(){if(deliveryWebhookUrl.isBlank())return;for(var entry:deliveries.entrySet()){var delivery=entry.getValue();
        if("pending".equals(delivery.status())&&delivery.updatedAt().plusSeconds(2).isBefore(Instant.now())){delivery=new DeliveryView(delivery.id(),"delivered",delivery.fee(),delivery.trackingUrl(),Instant.now());deliveries.put(entry.getKey(),delivery);}
        if("delivered".equals(delivery.status())&&!deliveryNotified.contains(delivery.id()))try{var timestamp=Long.toString(Instant.now().getEpochSecond());var eventId="devt_"+delivery.id();
            var payload=json.writeValueAsBytes(Map.of("id",eventId,"kind","delivery.status_changed","created",Instant.now().toString(),"delivery_id",delivery.id(),"status","delivered"));
            RestClient.create().post().uri(deliveryWebhookUrl).contentType(MediaType.APPLICATION_JSON).header("X-Timestamp",timestamp).header("X-Uber-Signature",hmac(deliveryWebhookKey,timestamp,payload)).body(payload).retrieve().toBodilessEntity();deliveryNotified.add(delivery.id());
        }catch(RuntimeException ignored){/* retry on the next tick */}}}
    private static String hmac(String key,String timestamp,byte[] payload){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));mac.update((byte)'.');return HexFormat.of().formatHex(mac.doFinal(payload));}catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
}
