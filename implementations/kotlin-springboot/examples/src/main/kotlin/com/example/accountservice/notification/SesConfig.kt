package com.example.accountservice.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import java.net.URI

@Configuration
class SesConfig {

    @Bean
    fun sesClient(
        @Value("\${AWS_REGION:us-east-1}") region: String,
        @Value("\${AWS_ACCESS_KEY_ID:test}") accessKeyId: String,
        @Value("\${AWS_SECRET_ACCESS_KEY:test}") secretAccessKey: String,
        @Value("\${AWS_ENDPOINT_URL:}") endpointUrl: String,
    ): SesClient {
        // 자격 증명은 항상 명시적으로 설정한다. DefaultCredentialsProvider(IMDS 등)에 의존하면
        // 자격 증명이 없을 때 탐색 과정에서 응답이 느려질 수 있다.
        val builder = SesClient.builder()
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
            )

        if (endpointUrl.isNotBlank()) {
            builder.endpointOverride(URI.create(endpointUrl))
        }

        return builder.build()
    }
}
