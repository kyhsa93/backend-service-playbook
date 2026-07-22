package com.example.accountservice.taskqueue

import com.example.accountservice.config.SqsProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

/**
 * The Consumer dedicated to the Task Queue (SQS FIFO) — intentionally a separate component from
 * [com.example.accountservice.outbox.OutboxConsumer] (dedicated to the Domain/Integration Event Queue).
 * The two Consumers subscribe to different queues and route through different registries
 * ([TaskHandlerRegistry] vs [com.example.accountservice.outbox.EventHandlerRegistry]) — this keeps the
 * distinction that root scheduling.md/domain-events.md prescribes ("Task Queue (command) and Domain
 * Event (fact) are different units of meaning") at the component level too (a single Consumer is never
 * merged to handle both queues).
 *
 * The rest of the structure (why it's expressed with a dedicated thread, handler success = ack /
 * failure = redelivery) is the same as OutboxConsumer — see that class's KDoc.
 */
@Component
class TaskQueueConsumer(
    private val sqsClient: SqsClient,
    private val registry: TaskHandlerRegistry,
    private val sqsProperties: SqsProperties,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(TaskQueueConsumer::class.java)

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    override fun start() {
        running = true
        workerThread =
            Thread(::pollLoop, "task-queue-consumer").apply {
                isDaemon = false
                start()
            }
    }

    override fun stop() {
        running = false
        workerThread?.join(SHUTDOWN_JOIN_TIMEOUT_MS)
    }

    override fun isRunning(): Boolean = running

    private fun pollLoop() {
        while (running) {
            try {
                val result =
                    sqsClient.receiveMessage(
                        ReceiveMessageRequest
                            .builder()
                            .queueUrl(sqsProperties.taskQueueUrl)
                            .maxNumberOfMessages(10)
                            .messageAttributeNames("taskType", "taskId")
                            .waitTimeSeconds(5)
                            .build(),
                    )
                result.messages().forEach { handle(it) }
            } catch (e: Exception) {
                if (running) {
                    logger.error("Failed to receive from Task Queue (SQS)", e)
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun handle(message: Message) {
        val taskType = message.messageAttributes()["taskType"]?.stringValue()
        val taskId = message.messageAttributes()["taskId"]?.stringValue() ?: ""
        try {
            if (taskType == null) throw IllegalStateException("Missing taskType message attribute.")
            registry.dispatch(taskType, message.body())
            sqsClient.deleteMessage(
                DeleteMessageRequest
                    .builder()
                    .queueUrl(sqsProperties.taskQueueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build(),
            )
        } catch (e: Exception) {
            // Don't delete it — it will be received again and retried once the visibility timeout
            // passes (at-least-once). Once maxReceiveCount (3) is exceeded, SQS automatically moves it
            // to the DLQ (scheduling.md's DLQ section).
            logger
                .atError()
                .addKeyValue("task_type", taskType)
                .addKeyValue("task_id", taskId)
                .setCause(e)
                .log("Failed to process task")
        }
    }

    companion object {
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 10_000L
    }
}
