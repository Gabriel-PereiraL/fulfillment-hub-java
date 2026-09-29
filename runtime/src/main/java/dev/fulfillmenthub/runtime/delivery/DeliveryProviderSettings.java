package dev.fulfillmenthub.runtime.delivery;
import java.net.URI;import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("fulfillment.providers.delivery")
public record DeliveryProviderSettings(boolean enabled,URI baseUrl,String customerId,String clientId,String clientSecret){}
