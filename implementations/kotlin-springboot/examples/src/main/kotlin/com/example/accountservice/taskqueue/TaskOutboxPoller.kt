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
 * Handles only publishing `task_outbox` -> SQS (FIFO Task Queue) — it has exactly the same shape as
 * [com.example.accountservice.outbox.OutboxPoller] (they share the same SqsClient bean but target a
 * different queue). Sending MessageGroupId/MessageDeduplicationId along with it is the only difference
 * from the Domain Event Queue (a standard queue) — a FIFO queue requires both of these attributes.
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
            logger.error("Task Outbox polling failed", e)
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
                        .log("Failed to publish to Task Queue (SQS FIFO)")
                }
        }
    }
}
