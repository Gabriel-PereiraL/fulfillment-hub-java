package dev.fulfillmenthub.runtime.providers;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Service
public final class ProviderResilience {
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    public ProviderResilience() { this.meters = null; }
    @Autowired public ProviderResilience(ObjectProvider<MeterRegistry> meters) { this.meters = meters.getIfAvailable(); }

    public <T> T execute(String provider, Supplier<T> call) {
        var breaker = breakers.computeIfAbsent(provider, this::newBreaker);
        var retry = retries.computeIfAbsent(provider, this::newRetry);
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call)).get();
    }

    CircuitBreaker newBreaker(String name) {
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(10)
                .slidingWindowSize(20)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(ProviderResilience::retryable)
                .build();
        var breaker=CircuitBreaker.of(name + "-provider", config);
        if(meters!=null)io.micrometer.core.instrument.Gauge.builder("fh.provider.circuit.state",breaker,b->b.getState().ordinal())
                .tag("provider",name).register(meters);
        return breaker;
    }

    Retry newRetry(String name) {
        var config = RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(500, 2.0, 0.5))
                .retryOnException(ProviderResilience::retryable)
                .build();
        var retry=Retry.of(name + "-provider", config);
        if(meters!=null)retry.getEventPublisher().onRetry(event->meters.counter("fh.provider.retries","provider",name).increment());
        return retry;
    }

    static boolean retryable(Throwable failure) {
        if (failure instanceof ResourceAccessException) return true;
        if (failure instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }
        return false;
    }

    CircuitBreaker circuit(String provider) { return breakers.get(provider); }
}
