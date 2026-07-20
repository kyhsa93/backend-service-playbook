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
 * AWS SQS 클라이언트 빈 설정. {@code account/infrastructure/notification/SesConfig}와 동일한 구성(항상 정적
 * credential 명시, {@code AwsProperties.endpointUrl()}이 설정되면 LocalStack으로 엔드포인트 오버라이드)을 따른다. {@link
 * OutboxPoller}와 {@link OutboxConsumer}가 이 하나의 클라이언트를 공유한다.
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
