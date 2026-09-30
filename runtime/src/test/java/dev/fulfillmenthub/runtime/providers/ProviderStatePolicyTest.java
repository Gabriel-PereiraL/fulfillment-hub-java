package dev.fulfillmenthub.runtime.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderStatePolicyTest {
    private static final Instant NOW=Instant.parse("2026-09-30T10:00:00Z");
    @Test void paidPaymentCannotRegressToPending(){assertEquals(ProviderStatePolicy.Decision.Conflict,ProviderStatePolicy.payment("Paid","Pending",NOW,NOW.plusSeconds(1)));}
    @Test void stalePaymentSnapshotIsIgnored(){assertEquals(ProviderStatePolicy.Decision.Stale,ProviderStatePolicy.payment("Authorized","Paid",NOW,NOW.minusSeconds(1)));}
    @Test void paidCanAdvanceToRefunded(){assertEquals(ProviderStatePolicy.Decision.Applied,ProviderStatePolicy.payment("Paid","Refunded",NOW,NOW.plusSeconds(1)));}
    @Test void deliveredDeliveryCannotRegress(){assertEquals(ProviderStatePolicy.Decision.Conflict,ProviderStatePolicy.delivery("Delivered","Requested",NOW,NOW.plusSeconds(1)));}
    @Test void deliveryAdvancesInOrder(){assertEquals(ProviderStatePolicy.Decision.Applied,ProviderStatePolicy.delivery("Requested","Dropoff",NOW,NOW.plusSeconds(1)));}
}
