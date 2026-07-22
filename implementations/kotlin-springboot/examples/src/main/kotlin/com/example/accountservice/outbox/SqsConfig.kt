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
 * The [SqsClient] bean shared by [OutboxPoller] (publishing) and [OutboxConsumer] (receiving).
 *
 * Configured the same way as
 * [com.example.accountservice.notification.infrastructure.SesConfig] — credentials are always set
 * explicitly (relying on DefaultCredentialsProvider/IMDS lookup can slow down the response when
 * credentials aren't available). `outbox/` is shared infrastructure that doesn't belong to any BC, so
 * this configuration lives here rather than in `account/infrastructure/`.
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
