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
class SesConfig(private val awsProperties: AwsProperties) {

    @Bean
    fun sesClient(): SesClient {
        // 자격 증명은 항상 명시적으로 설정한다. DefaultCredentialsProvider(IMDS 등)에 의존하면
        // 자격 증명이 없을 때 탐색 과정에서 응답이 느려질 수 있다.
        val builder = SesClient.builder()
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
