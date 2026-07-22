package com.example.accountservice.notification.infrastructure

import com.example.accountservice.config.AwsProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import java.net.URI

@Configuration
class SesConfig(
    private val awsProperties: AwsProperties,
) {
    @Bean
    fun sesClient(): SesClient {
        // Always set credentials explicitly. Relying on DefaultCredentialsProvider (IMDS, etc.) can slow
        // down the response during the lookup process when credentials aren't available.
        val builder =
            SesClient
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
