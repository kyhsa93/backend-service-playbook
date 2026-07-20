package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code OutboxPoller}가 발행하고 {@code OutboxConsumer}가 수신하는 공유 Domain/Integration Event SQS 큐의 URL. 큐
 * 자체는 LocalStack(로컬)/실제 SQS(운영)에 {@code localstack/init-sqs.sh}와 동일한 구성(DLQ + RedrivePolicy)으로 미리
 * 생성돼 있어야 한다 — 애플리케이션은 URL만 안다.
 */
@ConfigurationProperties(prefix = "sqs")
@Validated
public record SqsProperties(@NotBlank String domainEventQueueUrl) {}
