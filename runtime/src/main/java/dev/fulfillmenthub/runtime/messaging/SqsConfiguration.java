package dev.fulfillmenthub.runtime.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnProperty(name="fulfillment.messaging.enabled",havingValue="true")
public class SqsConfiguration {
    @Bean SqsClient sqsClient(MessagingSettings settings) {
        return SqsClient.builder().endpointOverride(settings.endpoint()).region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"))).build();
    }
}
