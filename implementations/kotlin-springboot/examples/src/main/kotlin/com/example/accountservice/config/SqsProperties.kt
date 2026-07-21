package com.example.accountservice.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "sqs")
data class SqsProperties(
    @field:NotBlank
    val domainEventQueueUrl: String,
    // Task Queue(scheduling.md) 전용 SQS FIFO 큐 URL — Domain/Integration Event 큐(domainEventQueueUrl,
    // 표준 큐)와 의도적으로 분리한다. "명령(Task): X를 수행하라"와 "사실(Domain Event): X가
    // 일어났다"는 의미 단위가 다르고(domain-events.md), Task Queue는 날짜/월 기반 deduplicationId로
    // 다중 인스턴스 중복 적재를 막아야 하므로 FIFO 큐가 필요하다(표준 큐는 MessageDeduplicationId를
    // 지원하지 않는다).
    @field:NotBlank
    val taskQueueUrl: String,
)
