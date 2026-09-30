package dev.fulfillmenthub.runtime.identity;

import static org.junit.jupiter.api.Assertions.*;

import dev.fulfillmenthub.runtime.RuntimeConfiguration;
import dev.fulfillmenthub.domain.Address;
import dev.fulfillmenthub.domain.Money;
import dev.fulfillmenthub.runtime.orders.OrderPlacementService;
import dev.fulfillmenthub.runtime.orders.ProductRow;
import dev.fulfillmenthub.runtime.orders.OrderService;
import dev.fulfillmenthub.runtime.outbox.OutboxProcessor;
import dev.fulfillmenthub.runtime.outbox.OutboxRow;
import dev.fulfillmenthub.runtime.payment.PaymentWorkflowService;
import dev.fulfillmenthub.runtime.payment.PaymentGatewayClient;
import dev.fulfillmenthub.runtime.payment.PaymentProviderSettings;
import dev.fulfillmenthub.runtime.providers.ProviderResilience;
import dev.fulfillmenthub.runtime.delivery.DeliveryWorkflowService;
import dev.fulfillmenthub.runtime.delivery.DeliveryProviderSettings;
import dev.fulfillmenthub.runtime.idempotency.IdempotencyService;
import dev.fulfillmenthub.runtime.messaging.SqsBroker;
import dev.fulfillmenthub.runtime.messaging.SqsConfiguration;
import dev.fulfillmenthub.runtime.messaging.MessagingSettings;
import dev.fulfillmenthub.runtime.webhook.WebhookInboxService;
import dev.fulfillmenthub.runtime.webhook.WebhookProcessingService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import com.sun.net.httpserver.HttpServer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionServiceIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties({TokenSettings.class, MessagingSettings.class})
    @EntityScan("dev.fulfillmenthub.runtime")
    @Import({RuntimeConfiguration.class, SessionService.class, OrderPlacementService.class, OrderService.class,
            OutboxProcessor.class, WebhookInboxService.class, WebhookProcessingService.class, PaymentWorkflowService.class, IdempotencyService.class, SqsConfiguration.class, SqsBroker.class})
    static class TestApplication {}

    private ConfigurableApplicationContext context;
    private SessionService sessions;
    private PasswordEncoder passwords;
    private TransactionTemplate tx;
    private EntityManager em;
    private OrderPlacementService orders;
    private OrderService orderQueries;
    private OutboxProcessor outbox;
    private WebhookInboxService webhooks;
    private WebhookProcessingService webhookProcessor;
    private SqsBroker broker;
    private IdempotencyService idempotency;

    private UUID userId;

    @BeforeAll
    void startContext() {
        context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + requiredProperty("it.database.url"),
                        "spring.datasource.username=" + requiredProperty("it.database.user"),
                        "spring.datasource.password=" + requiredProperty("it.database.password"),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.flyway.enabled=false",
                        "fulfillment.security.signing-key=integration-test-signing-key-with-at-least-32-bytes", // gitleaks:allow - fixture local sem valor fora do teste
                        "fulfillment.security.issuer=fulfillmenthub",
                        "fulfillment.security.audience=fulfillmenthub-api",
                        "fulfillment.security.access-minutes=15",
                        "fulfillment.security.session-days=7",
                        "fulfillment.messaging.enabled=true",
                        "fulfillment.messaging.endpoint=" + requiredProperty("it.sqs.endpoint"),
                        "fulfillment.messaging.region=us-east-1",
                        "fulfillment.messaging.domain-queue=fh-domain-events-it",
                        "fulfillment.messaging.domain-dlq=fh-domain-events-it-dlq",
                        "fulfillment.messaging.webhook-queue=fh-webhooks-inbound-it",
                        "fulfillment.messaging.webhook-dlq=fh-webhooks-inbound-it-dlq")
                .run();
        sessions = context.getBean(SessionService.class);
        passwords = context.getBean(PasswordEncoder.class);
        tx = context.getBean(TransactionTemplate.class);
        em = context.getBean(EntityManager.class);
        orders = context.getBean(OrderPlacementService.class);
        orderQueries = context.getBean(OrderService.class);
        outbox = context.getBean(OutboxProcessor.class);
        webhooks = context.getBean(WebhookInboxService.class);
        webhookProcessor = context.getBean(WebhookProcessingService.class);
        broker = context.getBean(SqsBroker.class);
        idempotency = context.getBean(IdempotencyService.class);
    }

    @AfterAll
    void stopContext() { context.close(); }

    private static String requiredProperty(String name) {
        var value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing test property: " + name);
        return value;
    }

    @BeforeEach
    void resetDatabase() {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("delete from webhook_events").executeUpdate();
            em.createNativeQuery("delete from delivery_events").executeUpdate();
            em.createNativeQuery("delete from deliveries").executeUpdate();
            em.createNativeQuery("delete from delivery_quotes").executeUpdate();
            em.createNativeQuery("delete from processed_messages").executeUpdate();
            em.createNativeQuery("delete from idempotency_records").executeUpdate();
            em.createNativeQuery("delete from refresh_credentials").executeUpdate();
            em.createNativeQuery("delete from auth_sessions").executeUpdate();
            em.createNativeQuery("delete from users").executeUpdate();
            em.createNativeQuery("delete from outbox_messages").executeUpdate();
            em.createNativeQuery("delete from payment_attempts").executeUpdate();
            em.createNativeQuery("delete from payments").executeUpdate();
            em.createNativeQuery("delete from order_status_changes").executeUpdate();
            em.createNativeQuery("delete from order_items").executeUpdate();
            em.createNativeQuery("delete from orders").executeUpdate();
            em.createNativeQuery("delete from products").executeUpdate();
            var user = UserRow.create("customer@example.com", passwords.encode("correct-password"), "Customer");
            userId = user.id;
            em.persist(user);
        });
    }

    @Test
    void loginStoresOnlyRefreshHashAndAuthenticatesSession() {
        var tokens = sessions.login("CUSTOMER@example.com", "correct-password");

        assertNotNull(tokens);
        assertTrue(sessions.validate(userId, tokens.sessionId()));
        assertEquals("Bearer", tokens.tokenType());
        var storedHash = tx.execute(status -> em.createQuery(
                "select r.hash from RefreshRow r where r.sessionId=:session", String.class)
                .setParameter("session", tokens.sessionId()).getSingleResult());
        assertEquals(SessionService.hash(tokens.refreshToken()), storedHash);
        assertNotEquals(tokens.refreshToken(), storedHash);
    }

    @Test
    void replayOfRotatedRefreshTokenRevokesTheWholeSession() {
        var first = sessions.login("customer@example.com", "correct-password");
        assertNotNull(first);
        var rotated = sessions.refresh(first.refreshToken());
        assertNotNull(rotated);
        assertTrue(sessions.validate(userId, first.sessionId()));

        assertNull(sessions.refresh(first.refreshToken()));
        assertFalse(sessions.validate(userId, first.sessionId()));
        assertNull(sessions.refresh(rotated.refreshToken()));
    }

    @Test
    void revokingAnotherOwnedSessionLeavesCallerActive() {
        var caller = sessions.login("customer@example.com", "correct-password");
        var target = sessions.login("customer@example.com", "correct-password");
        assertNotNull(caller);
        assertNotNull(target);

        assertTrue(sessions.revoke(userId, caller.sessionId(), target.sessionId()));
        assertTrue(sessions.validate(userId, caller.sessionId()));
        assertFalse(sessions.validate(userId, target.sessionId()));
    }

    @Test
    void orderAndStockReservationAndOutboxCommitAtomically() {
        var productId = seedProduct(2);
        var placed = orders.place(UUID.randomUUID(), address(), List.of(new OrderPlacementService.Line(productId, 2)),
                Money.brl("12.00"), "checkout-1");

        assertEquals(0, scalar("select stock_quantity from products where id='" + productId + "'"));
        assertEquals(1, scalar("select count(*) from orders where id='" + placed.id() + "'"));
        assertEquals(1, scalar("select count(*) from outbox_messages where aggregate_id='" + placed.id() + "'"));
        assertEquals(new BigDecimal("32.00"), placed.total().amount());
    }

    @Test
    void committedOrderIsRecoveredWhenIdempotencyCompletionNeverRan() {
        var productId=seedProduct(1);var customer=UUID.randomUUID();var scope=UUID.randomUUID().toString();var key="crash-window";var hash="ABC123";
        assertEquals(IdempotencyService.State.Started,idempotency.begin(scope,key,hash).state());
        var first=orders.place(customer,address(),List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),key,scope,key);
        var recovered=idempotency.begin(scope,key,hash);
        assertEquals(IdempotencyService.State.Recovered,recovered.state());assertEquals(first.id(),recovered.orderId());
        assertEquals(1,scalar("select count(*) from orders where idempotency_key='"+key+"'"));assertEquals(0,scalar("select stock_quantity from products where id='"+productId+"'"));
        idempotency.release(scope,key);assertEquals(1,scalar("select count(*) from idempotency_records where scope='"+scope+"' and key='"+key+"'"));
    }

    @Test
    void twoConcurrentBuyersCannotShareTheLastUnit() throws Exception {
        var productId = seedProduct(1);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                gate.await();
                try {
                    orders.place(UUID.randomUUID(), address(), List.of(new OrderPlacementService.Line(productId, 1)),
                            Money.brl("0.00"), UUID.randomUUID().toString());
                    return true;
                } catch (RuntimeException expected) { return false; }
            };
            var first = executor.submit(task); var second = executor.submit(task); gate.countDown();
            assertNotEquals(first.get(), second.get());
        }
        assertEquals(0, scalar("select stock_quantity from products where id='" + productId + "'"));
        assertEquals(1, scalar("select count(*) from orders"));
        assertEquals(1, scalar("select count(*) from outbox_messages"));
    }

    @Test
    void customerVisibilityPaginationAndCancellationPreserveStockExactlyOnce() {
        var productId=seedProduct(5);var customer=UUID.randomUUID();var actor=UUID.randomUUID();
        var first=orders.place(customer,address(),List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),"one");
        var second=orders.place(customer,address(),List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),"two");
        var third=orders.place(customer,address(),List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),"three");
        assertNull(orderQueries.get(first.id(),UUID.randomUUID(),false));
        var page1=orderQueries.list(customer,false,null,2,null);assertEquals(List.of(third.id(),second.id()),page1.items().stream().map(OrderService.Summary::id).toList());
        assertNotNull(page1.nextCursor());var page2=orderQueries.list(customer,false,page1.nextCursor(),2,null);
        assertEquals(List.of(first.id()),page2.items().stream().map(OrderService.Summary::id).toList());assertNull(page2.nextCursor());
        var cancelled=orderQueries.cancel(first.id(),customer,actor,false,null);assertEquals("Cancelled",cancelled.status());
        assertEquals("CustomerRequest",cancelled.cancellationReason());orderQueries.cancel(first.id(),customer,actor,false,null);
        assertEquals(3,scalar("select stock_quantity from products where id='"+productId+"'"));
        assertEquals(4,scalar("select count(*) from outbox_messages"));
    }

    @Test
    void customerCannotCancelAnotherCustomersOrderOrScheduleProviderEffects() {
        var productId=seedProduct(1);var owner=UUID.randomUUID();
        var placed=orders.place(owner,address(),List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),"owned-order");
        var outboxBefore=scalar("select count(*) from outbox_messages");

        assertNull(orderQueries.cancel(placed.id(),UUID.randomUUID(),UUID.randomUUID(),false,null));

        assertEquals("Created",orderQueries.get(placed.id(),owner,false).status());
        assertEquals(0,scalar("select stock_quantity from products where id='"+productId+"'"));
        assertEquals(outboxBefore,scalar("select count(*) from outbox_messages"));
        assertEquals(0,scalar("select count(*) from outbox_messages where aggregate_id='"+placed.id()+"' and type='OrderCancelled'"));
    }

    @Test
    void outboxNeverAcknowledgesRequiredEventsWithoutAHandlerAndParksPoisonAfterFiveAttempts() {
        var known=OutboxRow.pending("OrderPaid",UUID.randomUUID(),Instant.now());
        var poison=OutboxRow.pending("UnrecognizedPoison",UUID.randomUUID(),Instant.now());
        tx.executeWithoutResult(status->{em.persist(known);em.persist(poison);});
        assertEquals(2,outbox.drain(50));
        assertEquals(1,scalar("select count(*) from outbox_messages where id='"+known.id+"' and status='Pending' and attempts=1"));
        for(int attempt=1;attempt<5;attempt++){
            tx.executeWithoutResult(status->em.createNativeQuery("update outbox_messages set next_attempt_at=now() where id=:id")
                    .setParameter("id",poison.id).executeUpdate());
            assertEquals(1,outbox.drain(50));
        }
        assertEquals(1,scalar("select count(*) from outbox_messages where id='"+poison.id+"' and status='Failed' and attempts=5"));
        assertTrue(outbox.requeue(poison.id));
        assertEquals(1,scalar("select count(*) from outbox_messages where id='"+poison.id+"' and status='Pending' and attempts=0"));
    }

    @Test
    void webhookAuthenticatesRawBytesRejectsTamperingAndStoresDuplicatesOnce() throws Exception {
        var key = "integration-webhook-key";
        var body = "{\"id\":\"evt-1\",\"type\":\"payment.status_changed\",\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        var timestamp = Long.toString(Instant.now().getEpochSecond());
        var signature = hmac(key, timestamp, body);

        assertEquals(WebhookInboxService.Verification.Valid,
                webhooks.verify(key, body, signature, timestamp, 300));
        assertEquals(WebhookInboxService.Verification.InvalidSignature,
                webhooks.verify(key, "{}".getBytes(StandardCharsets.UTF_8), signature, timestamp, 300));
        assertEquals(WebhookInboxService.Verification.StaleTimestamp,
                webhooks.verify(key, body, hmac(key, "1", body), "1", 300));
        assertEquals(WebhookInboxService.Verification.MissingSignature,
                webhooks.verify(key, body, null, timestamp, 300));

        var identity = webhooks.identify(body);
        assertEquals("evt-1", identity.id());
        var first = webhooks.store("payments", identity, body, "corr-1");
        var duplicate = webhooks.store("payments", identity, body, "corr-2");
        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.id(), duplicate.id());
        assertEquals(1, scalar("select count(*) from webhook_events where payload->>'id'='evt-1'"));
    }

    @Test
    void twoWorkersCannotClaimTheSameWebhookAndExpiredLeaseIsRecoverable() throws Exception {
        var body="{\"id\":\"evt-claim\",\"type\":\"informational.event\"}".getBytes(StandardCharsets.UTF_8);var receipt=webhooks.store("unknown",webhooks.identify(body),body,"corr-claim");var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){var task=(java.util.concurrent.Callable<Boolean>)()->{gate.await();return webhookProcessor.processOne(receipt.id());};var first=executor.submit(task);var second=executor.submit(task);gate.countDown();assertNotEquals(first.get(),second.get());}
        assertEquals(1,scalar("select attempts from webhook_events where id='"+receipt.id()+"'"));
        tx.executeWithoutResult(status->em.createNativeQuery("update webhook_events set status='Processing',owner=:owner,locked_until=now()-interval '1 second',next_attempt_at=now() where id=:id").setParameter("owner",UUID.randomUUID()).setParameter("id",receipt.id()).executeUpdate());
        assertTrue(webhookProcessor.processOne(receipt.id()));assertEquals(2,scalar("select attempts from webhook_events where id='"+receipt.id()+"'"));
    }

    @Test
    void orderPlacedOutboxCreatesOneDurablePaymentAndAdvancesOrder() {
        var productId = seedProduct(1);
        var placed = orders.place(UUID.randomUUID(), address(),
                List.of(new OrderPlacementService.Line(productId, 1)), Money.brl("15"), "payment-flow");

        assertEquals(1, outbox.drain(50));
        assertEquals(1, broker.consume());
        assertEquals(1, scalar("select count(*) from payments where order_id='" + placed.id() + "' and status='Pending'"));
        assertEquals(1, scalar("select count(*) from orders where id='" + placed.id() + "' and status='AwaitingPayment'"));
        assertEquals(1, scalar("select count(*) from outbox_messages where aggregate_id='" + placed.id() + "' and status='Processed'"));
    }

    @Test
    void paymentRecoversWhenProviderExecutesButEveryResponseIsLost() throws Exception {
        var productId=seedProduct(1);var placed=orders.place(UUID.randomUUID(),address(),
                List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),"lost-payment-response");
        assertEquals(1,outbox.drain(50));assertEquals(1,broker.consume());
        var paymentId=tx.execute(status->(UUID)em.createNativeQuery("select id from payments where order_id=:order")
                .setParameter("order",placed.id()).getSingleResult());
        var providerEffects=new AtomicInteger();var providerKeys=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();var returnResponses=new AtomicBoolean();var providerStatus=new java.util.concurrent.atomic.AtomicReference<>("pending");var remoteId="pay_"+UUID.randomUUID().toString().replace("-","");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/payments/v1/payments",exchange->{
            var providerKey=exchange.getRequestHeaders().getFirst("Idempotency-Key");if(providerKey!=null&&providerKeys.add(providerKey))providerEffects.incrementAndGet();exchange.getRequestBody().readAllBytes();
            if(!returnResponses.get()){exchange.close();return;}
            var json=("{\"id\":\"%s\",\"status\":\"%s\",\"amount\":{\"amount\":\"25.00\",\"currency\":\"BRL\"},\"failure_code\":null,\"updated_at\":\"%s\"}")
                    .formatted(remoteId,providerStatus.get(),Instant.now());var body=json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var gateway=new PaymentGatewayClient(em,tx,java.time.Clock.systemUTC(),
                    new PaymentProviderSettings(true, URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-token"),new ProviderResilience());
            assertThrows(org.springframework.web.client.RestClientException.class,()->gateway.submit(paymentId));
            assertEquals("Unknown",textScalar("select status from payments where id='"+paymentId+"'"));
            assertEquals("Unknown",textScalar("select outcome from payment_attempts where payment_id='"+paymentId+"'"));
            returnResponses.set(true);assertTrue(gateway.resume(paymentId));
            assertEquals(remoteId,textScalar("select provider_payment_id from payments where id='"+paymentId+"'"));
            assertEquals(2,scalar("select count(*) from payment_attempts where payment_id='"+paymentId+"'"));
            assertEquals(1,providerEffects.get(),"the stable idempotency key must represent one remote payment");
            assertEquals(1,providerKeys.size());
            providerStatus.set("paid");assertTrue(gateway.verifyAndApply(remoteId));assertEquals("Paid",textScalar("select status from payments where id='"+paymentId+"'"));
            providerStatus.set("pending");assertTrue(gateway.verifyAndApply(remoteId));assertEquals("Paid",textScalar("select status from payments where id='"+paymentId+"'"),"a later stale-state snapshot cannot regress a settled payment");
        }finally{server.stop(0);}
    }

    @Test
    void deliveryRecoversPersistedQuoteAndAdoptsDuplicateAfterLostResponse() throws Exception {
        var productId=seedProduct(1);var placed=orders.place(UUID.randomUUID(),address(),
                List.of(new OrderPlacementService.Line(productId,1)),Money.brl("15"),"lost-delivery-response");
        tx.executeWithoutResult(status->em.createNativeQuery("update orders set status='Paid' where id=:id").setParameter("id",placed.id()).executeUpdate());
        var keys=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();var returnResponses=new AtomicBoolean();var remoteId="del_"+UUID.randomUUID().toString().replace("-","");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/delivery",exchange->{var path=exchange.getRequestURI().getPath();exchange.getRequestBody().readAllBytes();
            String json;if(path.endsWith("/oauth/token"))json="{\"access_token\":\"sim_test\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"deliveries\"}";
            else if(path.endsWith("/delivery_quotes"))json=("{\"id\":\"quote-1\",\"fee\":{\"amount\":15.00,\"currency\":\"BRL\"},\"expires_at\":\"%s\",\"estimated_dropoff_at\":\"%s\",\"duration_minutes\":30,\"pickup_duration_minutes\":10}").formatted(Instant.now().plusSeconds(300),Instant.now().plusSeconds(1800));
            else{keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));if(!returnResponses.get()){exchange.close();return;}json=("{\"id\":\"%s\",\"status\":\"pending\",\"fee\":{\"amount\":15.00,\"currency\":\"BRL\"},\"tracking_url\":\"https://tracking.invalid/%s\",\"updated_at\":\"%s\"}").formatted(remoteId,remoteId,Instant.now());exchange.getResponseHeaders().add("X-Existing-Delivery-Id",remoteId);}
            var body=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(path.endsWith("/deliveries")&&returnResponses.get()?409:200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{var base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/delivery");var workflow=new DeliveryWorkflowService(em,tx,java.time.Clock.systemUTC(),new DeliveryProviderSettings(true,base,"customer","client","secret"),new ProviderResilience());
            assertThrows(org.springframework.web.client.RestClientException.class,()->workflow.request(placed.id()));assertEquals(1,scalar("select count(*) from deliveries where order_id='"+placed.id()+"'"));assertEquals(1,scalar("select count(*) from delivery_quotes where order_id='"+placed.id()+"'"));
            returnResponses.set(true);var localId=workflow.request(placed.id());assertNotNull(localId);assertEquals(remoteId,textScalar("select provider_delivery_id from deliveries where id='"+localId+"'"));assertEquals(1,keys.size());assertEquals(1,scalar("select count(*) from deliveries where order_id='"+placed.id()+"'"));
        }finally{server.stop(0);}
    }

    private static String hmac(String key, String timestamp, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private UUID seedProduct(int stock) {
        var id = UUID.randomUUID();
        tx.executeWithoutResult(status -> em.persist(ProductRow.create(id, "SKU-" + id.toString().substring(0, 8),
                "Test product", new BigDecimal("10.00"), stock, Instant.now())));
        return id;
    }
    private int scalar(String sql) {
        return tx.execute(status -> ((Number) em.createNativeQuery(sql).getSingleResult()).intValue());
    }
    private String textScalar(String sql){return tx.execute(status->String.valueOf(em.createNativeQuery(sql).getSingleResult()));}
    private static Address address() {
        return new Address("Main Street", "10", null, "Center", "Sao Paulo", "SP", "01001000", "BR", null, null);
    }
}
