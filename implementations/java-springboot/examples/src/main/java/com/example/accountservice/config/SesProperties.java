package com.example.accountservice.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "ses")
@Validated
public record SesProperties(@NotBlank @Email String senderEmail) {}
