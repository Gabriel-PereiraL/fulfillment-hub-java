package dev.fulfillmenthub.runtime.messaging;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fulfillment.messaging")
public record MessagingSettings(boolean enabled, boolean provision, URI endpoint, String region, String domainQueue,
                                String domainDlq, String webhookQueue, String webhookDlq) {}
