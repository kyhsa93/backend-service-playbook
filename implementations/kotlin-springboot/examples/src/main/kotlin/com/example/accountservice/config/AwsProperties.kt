package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "aws")
data class AwsProperties(
    @field:NotBlank
    val region: String,
    val endpointUrl: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
)
