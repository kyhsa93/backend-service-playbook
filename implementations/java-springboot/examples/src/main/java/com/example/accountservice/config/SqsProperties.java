package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SQS 큐 URL 모음. {@code domainEventQueueUrl}은 {@code OutboxPoller}가 발행하고 {@code OutboxConsumer}가
 * 수신하는 공유 Domain/Integration Event 큐(표준 큐)다. {@code taskQueueUrl}은 {@code
 * taskqueue/TaskOutboxPoller}가 발행하고 {@code taskqueue/TaskConsumer}가 수신하는 별도의 Task Queue(FIFO 큐)다 —
 * Task(명령: "X를 수행하라")는 Domain Event(사실: "X가 일어났다")와 개념적으로 다르므로 큐 자체를 분리한다(scheduling.md,
 * domain-events.md). 두 큐 모두 LocalStack(로컬)/실제 SQS(운영)에 {@code localstack/init-sqs.sh}와 동일한 구성(DLQ +
 * RedrivePolicy)으로 미리 생성돼 있어야 한다 — 애플리케이션은 URL만 안다.
 */
@ConfigurationProperties(prefix = "sqs")
@Validated
public record SqsProperties(@NotBlank String domainEventQueueUrl, @NotBlank String taskQueueUrl) {}
