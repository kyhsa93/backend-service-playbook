package com.example.accountservice.taskqueue

import com.example.accountservice.config.SqsProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/**
 * `task_outbox` → SQS(FIFO Task Queue) 발행만 담당한다 — [com.example.accountservice.outbox.OutboxPoller]와
 * 완전히 같은 모양이다(같은 SqsClient 빈을 공유하지만 대상 큐가 다르다). MessageGroupId/
 * MessageDeduplicationId를 함께 보내는 것이 Domain Event Queue(표준 큐)와의 유일한 차이다 — FIFO
 * 큐는 이 두 속성이 필수다.
 */
@Component
class TaskOutboxPoller(
    private val taskOutboxJpaRepository: TaskOutboxJpaRepository,
    private val sqsClient: SqsClient,
    private val sqsProperties: SqsProperties,
) {
    private val logger = LoggerFactory.getLogger(TaskOutboxPoller::class.java)

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
            logger.error("Task Outbox 폴링 실패", e)
        } finally {
            polling = false
        }
    }

    private fun drainOnce() {
        val pending = taskOutboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        for (row in pending) {
            runCatching {
                sqsClient.sendMessage(
                    SendMessageRequest
                        .builder()
                        .queueUrl(sqsProperties.taskQueueUrl)
                        .messageBody(row.payload)
                        .messageGroupId(row.groupId)
                        .messageDeduplicationId(row.deduplicationId)
                        .messageAttributes(
                            mapOf(
                                "taskType" to
                                    MessageAttributeValue
                                        .builder()
                                        .dataType("String")
                                        .stringValue(row.taskType)
                                        .build(),
                                "taskId" to
                                    MessageAttributeValue
                                        .builder()
                                        .dataType("String")
                                        .stringValue(row.taskId)
                                        .build(),
                            ),
                        ).build(),
                )
            }.onSuccess { row.markProcessed() }
                .onFailure {
                    logger
                        .atError()
                        .addKeyValue("task_type", row.taskType)
                        .addKeyValue("task_id", row.taskId)
                        .setCause(it)
                        .log("Task Queue(SQS FIFO) 발행 실패")
                }
        }
    }
}
