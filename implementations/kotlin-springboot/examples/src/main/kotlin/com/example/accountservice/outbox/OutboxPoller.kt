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
 * Handles only publishing from the Outbox table to SQS ("carries events accumulated in the DB into the
 * queue"). It never calls any EventHandler directly — that's [OutboxConsumer]'s job.
 *
 * The Command Service never references this class at all. Running independently and periodically via
 * `@Scheduled` is exactly what eliminates a "synchronous drain in the same process right after saving" —
 * even after the Command Service commits the save and returns a response, there's no way to know when
 * this event goes out to the queue until the next tick (up to 1 second later).
 *
 * `processed=true` means delivery to SQS has completed — it does not mean the handler has finished
 * processing. Any further retry / at-least-once guarantee is handled by SQS's visibility timeout + DLQ,
 * not by the outbox table (see docs/architecture/domain-events.md).
 */
@Component
class OutboxPoller(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val sqsClient: SqsClient,
    private val sqsProperties: SqsProperties,
) {
    private val logger = LoggerFactory.getLogger(OutboxPoller::class.java)

    // If the drain from the previous tick hasn't finished yet, don't run this one on top of it — this
    // guards against the case where there are more rows to process than the polling interval (1 second)
    // allows, making it take longer.
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
            logger.error("Outbox polling failed", e)
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
                        .messageAttributes(messageAttributesFor(row))
                        .build(),
                )
            }.onSuccess { row.markProcessed() }
                .onFailure {
                    // Rows that fail to publish are left as processed=false so they're retried on the
                    // next tick.
                    logger
                        .atError()
                        .addKeyValue("event_type", row.eventType)
                        .addKeyValue("event_id", row.eventId)
                        .setCause(it)
                        .log("Failed to publish to SQS")
                }
        }
    }

    // eventType/eventId travel as SQS message attributes rather than in the JSON body so
    // OutboxConsumer can dispatch/log without deserializing the payload first — traceparent
    // (observability.md) rides along the same way, and is simply omitted when the row has none.
    private fun messageAttributesFor(row: OutboxEvent): Map<String, MessageAttributeValue> =
        buildMap {
            put("eventType", stringAttribute(row.eventType))
            put("eventId", stringAttribute(row.eventId))
            row.traceparent?.let { put("traceparent", stringAttribute(it)) }
        }

    private fun stringAttribute(value: String): MessageAttributeValue =
        MessageAttributeValue
            .builder()
            .dataType("String")
            .stringValue(value)
            .build()
}
