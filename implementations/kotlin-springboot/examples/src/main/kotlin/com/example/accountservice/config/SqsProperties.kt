package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "sqs")
data class SqsProperties(
    @field:NotBlank
    val domainEventQueueUrl: String,
)
