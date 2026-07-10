package com.example.accountservice.common.infrastructure;

import com.example.accountservice.common.service.SecretService;
import org.springframework.beans.factory.annotation.Value;
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

    public SecretServiceImpl(
            @Value("${aws.region:us-east-1}") String region,
            @Value("${aws.endpoint-url:}") String endpointUrl
    ) {
        var builder = SecretsManagerClient.builder().region(Region.of(region));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
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
