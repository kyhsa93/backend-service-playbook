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
 * Injects the Secrets Manager value into the Environment at the earliest possible point, before the
 * ApplicationContext is prepared — subsequent @Value bindings use this value as-is.
 * It only runs in the prod profile, so it doesn't affect local/test startup speed or add a network
 * dependency there.
 */
class SecretsEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return

        val region = environment.getProperty("AWS_REGION", "us-east-1")
        val endpointUrl = environment.getProperty("AWS_ENDPOINT_URL", "")
        val accessKeyId = environment.getProperty("AWS_ACCESS_KEY_ID", "test")
        val secretAccessKey = environment.getProperty("AWS_SECRET_ACCESS_KEY", "test")

        val builder =
            SecretsManagerClient
                .builder()
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
