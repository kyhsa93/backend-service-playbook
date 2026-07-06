package com.example.accountservice.notification.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.SesClientBuilder;

import java.net.URI;

/**
 * AWS SES 클라이언트 빈 설정.
 * IMDS 등 기본 credential provider chain은 sandbox 환경에서 실패까지 지연이 크므로
 * 항상 정적 credential(StaticCredentialsProvider)을 명시적으로 사용한다.
 */
@Configuration
public class SesConfig {

    @Bean
    public SesClient sesClient(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl,
            @Value("${aws.access-key-id:test}") String accessKeyId,
            @Value("${aws.secret-access-key:test}") String secretAccessKey
    ) {
        SesClientBuilder builder = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }

        return builder.build();
    }
}
