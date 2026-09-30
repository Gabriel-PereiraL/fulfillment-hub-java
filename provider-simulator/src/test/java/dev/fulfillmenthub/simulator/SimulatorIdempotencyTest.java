package dev.fulfillmenthub.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimulatorIdempotencyTest {
    @Test
    void concurrentPaymentAndDeliveryCreationUseOneResourcePerKey() throws Exception {
        var simulator = new SimulatorController("payment-token", "client", "secret", "", "", "", "", JsonMapper.builder().build());
        var paymentRequest = new SimulatorController.PaymentRequest(
                new SimulatorController.Amount(new BigDecimal("10.00"), "BRL"), "order", "customer");
        var party = new SimulatorController.Party("customer",
                new SimulatorController.Address("street", "city", "SP", "01001000", "BR"), "+551100000000");
        var quote = simulator.quote("Bearer sim_token", "customer",
                new SimulatorController.QuoteRequest(party, party, new SimulatorController.Amount(BigDecimal.TEN, "BRL"))).getBody();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> payments = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> (Callable<String>) () -> simulator.createPayment("Bearer payment-token", "payment-key", paymentRequest).getBody().id())
                    .toList();
            List<Callable<String>> deliveries = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> (Callable<String>) () -> simulator.createDelivery("Bearer sim_token", "delivery-key", "customer",
                            new SimulatorController.DeliveryRequest(quote.id(), "order")).getBody().id())
                    .toList();
            var paymentIds = new HashSet<String>();
            for (var result : executor.invokeAll(payments)) paymentIds.add(result.get());
            var deliveryIds = new HashSet<String>();
            for (var result : executor.invokeAll(deliveries)) deliveryIds.add(result.get());
            assertEquals(1, paymentIds.size());
            assertEquals(1, deliveryIds.size());
        }
    }

    @Test
    void refundReplaysByKeyAndRejectsInvalidAmounts() {
        var simulator = new SimulatorController("payment-token", "client", "secret", "", "", "", "", JsonMapper.builder().build());
        var payment = simulator.createPayment("Bearer payment-token", "paid-payment",
                new SimulatorController.PaymentRequest(new SimulatorController.Amount(new BigDecimal("10.98"), "BRL"), "order", "customer")).getBody();
        var first = simulator.refund("Bearer payment-token", payment.id(), "refund-key", new SimulatorController.RefundRequest(new BigDecimal("10.98")));
        var replay = simulator.refund("Bearer payment-token", payment.id(), "refund-key", new SimulatorController.RefundRequest(new BigDecimal("10.98")));
        assertEquals(first.getBody().id(), replay.getBody().id());
        assertEquals(422, simulator.refund("Bearer payment-token", payment.id(), "negative", new SimulatorController.RefundRequest(BigDecimal.ONE.negate())).getStatusCode().value());
        assertEquals(422, simulator.refund("Bearer payment-token", payment.id(), "excess", new SimulatorController.RefundRequest(new BigDecimal("11.00"))).getStatusCode().value());
        assertEquals(409, simulator.refund("Bearer payment-token", payment.id(), "refund-key", new SimulatorController.RefundRequest(BigDecimal.ONE)).getStatusCode().value());
    }
}
