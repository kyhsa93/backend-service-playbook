package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * A collection of SQS queue URLs. {@code domainEventQueueUrl} is the shared Domain/Integration
 * Event queue (a standard queue) that {@code OutboxPoller} publishes to and {@code OutboxConsumer}
 * consumes from. {@code taskQueueUrl} is a separate Task Queue (a FIFO queue) that {@code
 * taskqueue/TaskOutboxPoller} publishes to and {@code taskqueue/TaskConsumer} consumes from — a
 * Task (a command: "do X") is conceptually different from a Domain Event (a fact: "X happened"), so
 * the queues themselves are kept separate (see scheduling.md, domain-events.md). Both queues must
 * already exist in LocalStack (local) / real SQS (production) with the same configuration (DLQ +
 * RedrivePolicy) as {@code localstack/init-sqs.sh} — the application only knows the URL.
 */
@ConfigurationProperties(prefix = "sqs")
@Validated
public record SqsProperties(@NotBlank String domainEventQueueUrl, @NotBlank String taskQueueUrl) {}
