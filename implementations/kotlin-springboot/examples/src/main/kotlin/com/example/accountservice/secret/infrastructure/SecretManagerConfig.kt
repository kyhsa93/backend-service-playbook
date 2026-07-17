package com.example.accountservice.secret.infrastructure

import com.example.accountservice.config.AwsProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.net.URI

@Configuration
class SecretManagerConfig(
    private val awsProperties: AwsProperties,
) {
    @Bean
    fun secretsManagerClient(): SecretsManagerClient {
        val builder =
            SecretsManagerClient
                .builder()
                .region(Region.of(awsProperties.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(awsProperties.accessKeyId, awsProperties.secretAccessKey),
                    ),
                )
        if (awsProperties.endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(awsProperties.endpointUrl))
        return builder.build()
    }
}
