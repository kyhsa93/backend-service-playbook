package com.example.accountservice.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Injects Secrets Manager values into the Environment at the earliest possible point, before the
 * ApplicationContext is prepared — subsequent @Value/@ConfigurationProperties bindings use this
 * value as-is. It only runs in the prod profile, so it does not affect local/test startup speed or
 * add a network dependency there.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;

        String region = environment.getProperty("aws.region", "us-east-1");
        String endpointUrl = environment.getProperty("aws.endpoint-url", "");

        var builder = SecretsManagerClient.builder().region(Region.of(region));
        if (!endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        SecretsManagerClient client = builder.build();

        try {
            String jwtSecretJson = client.getSecretValue(r -> r.secretId("app/jwt")).secretString();
            Map<String, Object> jwtSecret =
                    new ObjectMapper().readValue(jwtSecretJson, new TypeReference<>() {});

            Map<String, Object> props = Map.of("jwt.secret", jwtSecret.get("secret"));
            environment
                    .getPropertySources()
                    .addFirst(new MapPropertySource("secretsManager", props));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load secrets from Secrets Manager", e);
        } finally {
            client.close();
        }
    }
}
