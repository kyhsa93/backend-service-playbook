package com.example.accountservice.secret.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.Profiles
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.net.URI

/**
 * ApplicationContext가 준비되기 전, 가장 이른 시점에 Secrets Manager 값을
 * Environment에 주입한다 — 이후 @Value 바인딩이 이 값을 그대로 사용한다.
 * prod 프로필에서만 동작해 로컬/테스트 기동 속도와 네트워크 의존성에 영향을
 * 주지 않는다.
 */
class SecretsEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return

        val region = environment.getProperty("AWS_REGION", "us-east-1")
        val endpointUrl = environment.getProperty("AWS_ENDPOINT_URL", "")
        val accessKeyId = environment.getProperty("AWS_ACCESS_KEY_ID", "test")
        val secretAccessKey = environment.getProperty("AWS_SECRET_ACCESS_KEY", "test")

        val builder = SecretsManagerClient.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        if (endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(endpointUrl))
        val client = builder.build()

        client.use {
            val json = it.getSecretValue { r -> r.secretId("app/jwt") }.secretString()
            val secret = jacksonObjectMapper().readTree(json).get("secret").asText()
            environment.propertySources.addFirst(MapPropertySource("secretsManager", mapOf("jwt.secret" to secret)))
        }
    }
}
