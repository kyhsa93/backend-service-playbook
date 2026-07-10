package com.example.accountservice.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;
import java.util.Map;

/**
 * ApplicationContext가 준비되기 전, 가장 이른 시점에 Secrets Manager 값을
 * Environment에 주입한다 — 이후 @Value/@ConfigurationProperties 바인딩이
 * 이 값을 그대로 사용한다. prod 프로필에서만 동작해 로컬/테스트 기동 속도와
 * 네트워크 의존성에 영향을 주지 않는다.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
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
            Map<String, Object> jwtSecret = new ObjectMapper().readValue(jwtSecretJson, new TypeReference<>() {});

            Map<String, Object> props = Map.of("jwt.secret", jwtSecret.get("secret"));
            environment.getPropertySources().addFirst(new MapPropertySource("secretsManager", props));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load secrets from Secrets Manager", e);
        } finally {
            client.close();
        }
    }
}
