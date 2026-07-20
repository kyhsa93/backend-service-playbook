package com.example.accountservice.outbox

import com.example.accountservice.config.AwsProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

/**
 * [OutboxPoller](발행)와 [OutboxConsumer](수신)가 공유하는 [SqsClient] 빈.
 *
 * [com.example.accountservice.account.infrastructure.notification.SesConfig]와 동일한 구성이다 —
 * 자격 증명은 항상 명시적으로 설정한다(DefaultCredentialsProvider/IMDS 탐색에 의존하면 자격 증명이
 * 없을 때 응답이 느려질 수 있다). `outbox/`는 어느 BC에도 속하지 않는 공유 인프라이므로 이 설정을
 * `account/infrastructure/`가 아니라 여기 둔다.
 */
@Configuration
class SqsConfig(
    private val awsProperties: AwsProperties,
) {
    @Bean
    fun sqsClient(): SqsClient {
        val builder =
            SqsClient
                .builder()
                .region(Region.of(awsProperties.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(awsProperties.accessKeyId, awsProperties.secretAccessKey),
                    ),
                )

        if (awsProperties.endpointUrl.isNotBlank()) {
            builder.endpointOverride(URI.create(awsProperties.endpointUrl))
        }

        return builder.build()
    }
}
