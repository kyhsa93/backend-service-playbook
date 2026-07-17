package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "aws")
@Validated
public record AwsProperties(
        @NotBlank String region, String endpointUrl, String accessKeyId, String secretAccessKey) {}
