package dev.fulfillmenthub.runtime.providers;

import static org.junit.jupiter.api.Assertions.*;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ProviderResilienceTest {
    @Test void retriesTransientFailureAndReturnsSuccessfulAttempt() {
        var calls = new AtomicInteger();
        var resilience = new ProviderResilience();

        var value = resilience.execute("retry-test", () -> {
            if (calls.incrementAndGet() < 3) throw new ResourceAccessException("temporary");
            return "ok";
        });

        assertEquals("ok", value);
        assertEquals(3, calls.get());
    }

    @Test void doesNotRetryPermanentApplicationFailure() {
        var calls = new AtomicInteger();
        var resilience = new ProviderResilience();

        assertThrows(IllegalArgumentException.class, () -> resilience.execute("permanent-test", () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("invalid contract");
        }));
        assertEquals(1, calls.get());
    }

    @Test void opensCircuitAfterSustainedTransientFailures() {
        var calls = new AtomicInteger();
        var resilience = new ProviderResilience();

        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> resilience.execute("circuit-test", () -> {
                calls.incrementAndGet();
                throw new ResourceAccessException("down");
            }));
        }

        assertEquals(CircuitBreaker.State.OPEN, resilience.circuit("circuit-test").getState());
        int before = calls.get();
        assertThrows(CallNotPermittedException.class,
                () -> resilience.execute("circuit-test", () -> { calls.incrementAndGet(); return "unexpected"; }));
        assertEquals(before, calls.get());
    }
}
