package com.example.accountservice.outbox

import com.example.accountservice.config.SqsProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/**
 * Outbox 테이블 → SQS 발행만 담당한다("DB에 쌓인 이벤트를 큐로 실어 나른다"). 어떤 EventHandler도
 * 직접 호출하지 않는다 — 그건 [OutboxConsumer]의 몫이다.
 *
 * Command Service는 이 클래스를 전혀 참조하지 않는다. `@Scheduled`로 독립적으로 주기 실행되는 것
 * 자체가 "저장 직후 같은 프로세스 안에서 동기 드레인"을 제거하는 핵심이다 — Command Service가
 * 저장을 커밋하고 응답을 반환한 뒤에도, 이 이벤트가 언제 큐로 나가는지는 다음 tick(최대 1초 뒤)까지
 * 알 수 없다.
 *
 * `processed=true`는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는 뜻이다 —
 * 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의 visibility timeout + DLQ가
 * 담당한다(docs/architecture/domain-events.md 참고).
 */
@Component
class OutboxPoller(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val sqsClient: SqsClient,
    private val sqsProperties: SqsProperties,
) {
    private val logger = LoggerFactory.getLogger(OutboxPoller::class.java)

    // 이전 tick의 드레인이 아직 끝나지 않았으면 겹쳐 실행하지 않는다 — 폴링 주기(1초)보다 처리해야
    // 할 행이 많아 오래 걸리는 경우를 대비한다.
    @Volatile
    private var polling = false

    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun poll() {
        if (polling) return
        polling = true
        try {
            drainOnce()
        } catch (e: Exception) {
            logger.error("Outbox 폴링 실패", e)
        } finally {
            polling = false
        }
    }

    private fun drainOnce() {
        val pending = outboxEventJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        for (row in pending) {
            runCatching {
                sqsClient.sendMessage(
                    SendMessageRequest
                        .builder()
                        .queueUrl(sqsProperties.domainEventQueueUrl)
                        .messageBody(row.payload)
                        .messageAttributes(
                            mapOf(
                                "eventType" to
                                    MessageAttributeValue
                                        .builder()
                                        .dataType("String")
                                        .stringValue(row.eventType)
                                        .build(),
                                "eventId" to
                                    MessageAttributeValue
                                        .builder()
                                        .dataType("String")
                                        .stringValue(row.eventId)
                                        .build(),
                            ),
                        ).build(),
                )
            }.onSuccess { row.markProcessed() }
                .onFailure {
                    // 발행 실패 행은 processed=false로 남겨 다음 tick에서 재시도한다.
                    logger
                        .atError()
                        .addKeyValue("event_type", row.eventType)
                        .addKeyValue("event_id", row.eventId)
                        .setCause(it)
                        .log("SQS 발행 실패")
                }
        }
    }
}
