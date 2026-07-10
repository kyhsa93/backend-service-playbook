package com.example.accountservice.config

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "ses")
data class SesProperties(
    @field:NotBlank
    @field:Email
    val senderEmail: String,
)
