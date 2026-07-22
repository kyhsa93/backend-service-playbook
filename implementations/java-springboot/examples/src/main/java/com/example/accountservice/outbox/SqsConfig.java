package com.example.accountservice.outbox;

import com.example.accountservice.config.AwsProperties;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * AWS SQS client bean configuration. Follows the same setup as {@code
 * account/infrastructure/notification/SesConfig} (always specify static credentials explicitly,
 * override the endpoint to LocalStack if {@code AwsProperties.endpointUrl()} is set). {@link
 * OutboxPoller} and {@link OutboxConsumer} share this single client.
 */
@Configuration
@RequiredArgsConstructor
public class SqsConfig {

    private final AwsProperties awsProperties;

    @Bean
    public SqsClient sqsClient() {
        SqsClientBuilder builder =
                SqsClient.builder()
                        .region(Region.of(awsProperties.region()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                awsProperties.accessKeyId(),
                                                awsProperties.secretAccessKey())));

        if (awsProperties.endpointUrl() != null && !awsProperties.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(awsProperties.endpointUrl()));
        }

        return builder.build();
    }
}
