package dev.fulfillmenthub.runtime.webhook;
import jakarta.validation.constraints.*;import org.springframework.boot.context.properties.ConfigurationProperties;import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties("fulfillment.webhooks") public record WebhookSettings(@NotBlank @Size(min=16)String paymentKey,
 @NotBlank @Size(min=16)String deliveryKey,@Min(1)@Max(3600)long toleranceSeconds){}
