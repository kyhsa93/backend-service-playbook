package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "sqs")
data class SqsProperties(
    @field:NotBlank
    val domainEventQueueUrl: String,
    // The SQS FIFO queue URL dedicated to the Task Queue (scheduling.md) — intentionally separated
    // from the Domain/Integration Event queue (domainEventQueueUrl, a standard queue). "Command (Task):
    // perform X" and "Fact (Domain Event): X happened" are different units of meaning
    // (domain-events.md), and the Task Queue needs a FIFO queue because it uses a date/month-based
    // deduplicationId to prevent duplicate enqueuing across multiple instances (a standard queue
    // doesn't support MessageDeduplicationId).
    @field:NotBlank
    val taskQueueUrl: String,
)
