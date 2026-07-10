package com.example.accountservice.secret.infrastructure

import com.example.accountservice.secret.application.service.SecretService
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class SecretServiceImpl(private val client: SecretsManagerClient) : SecretService {

    private data class CacheEntry(val value: String, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttl = Duration.ofMinutes(5)

    override fun getSecret(secretId: String): String {
        cache[secretId]?.let { entry ->
            if (entry.expiresAt.isAfter(Instant.now())) return entry.value
        }

        val value = client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build()).secretString()
        cache[secretId] = CacheEntry(value, Instant.now().plus(ttl))
        return value
    }
}
