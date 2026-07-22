package com.example.accountservice.account.infrastructure.notification;

import com.example.accountservice.config.AwsProperties;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.SesClientBuilder;

/**
 * AWS SES client bean configuration. The default credential provider chain (IMDS, etc.) has a large
 * delay before failing in a sandbox environment, so static credentials (StaticCredentialsProvider)
 * are always used explicitly.
 */
@Configuration
@RequiredArgsConstructor
public class SesConfig {

    private final AwsProperties awsProperties;

    @Bean
    public SesClient sesClient() {
        SesClientBuilder builder =
                SesClient.builder()
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
