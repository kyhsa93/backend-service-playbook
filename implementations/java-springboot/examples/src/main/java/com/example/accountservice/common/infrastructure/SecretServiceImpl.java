package com.example.accountservice.common.infrastructure;

import com.example.accountservice.common.service.SecretService;
import com.example.accountservice.config.AwsProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SecretServiceImpl implements SecretService {

    private final SecretsManagerClient client;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(5);

    public SecretServiceImpl(AwsProperties awsProperties) {
        var builder = SecretsManagerClient.builder().region(Region.of(awsProperties.region()));
        if (awsProperties.endpointUrl() != null && !awsProperties.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(awsProperties.endpointUrl()));
        }
        this.client = builder.build();
    }

    @Override
    public String getSecret(String secretId) {
        CachedSecret cached = cache.get(secretId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        String value = client.getSecretValue(r -> r.secretId(secretId)).secretString();
        cache.put(secretId, new CachedSecret(value, Instant.now().plus(TTL)));
        return value;
    }

    private record CachedSecret(String value, Instant expiresAt) {}
}
