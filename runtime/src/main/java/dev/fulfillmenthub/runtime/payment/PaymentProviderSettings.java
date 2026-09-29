package dev.fulfillmenthub.runtime.payment;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fulfillment.providers.payment")
public record PaymentProviderSettings(boolean enabled, URI baseUrl, String token) {}
