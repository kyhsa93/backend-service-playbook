package com.example.accountservice.outbox

import com.example.accountservice.config.SqsProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

/**
 * Waits on SQS via long polling (`ReceiveMessageRequest.waitTimeSeconds`), and when a message arrives,
 * looks up the handler in [EventHandlerRegistry] by eventType (`MessageAttributes`) and invokes it.
 *
 * This repository doesn't use coroutines here (scheduling.md — it uses traditional thread-based
 * execution, which fits naturally with the Spring MVC + JPA blocking stack). The reason it isn't
 * expressed with `@Scheduled` is that `@Scheduled` is the right abstraction for work that "runs briefly
 * on a fixed period and returns" (e.g. [OutboxPoller]), whereas this Consumer is a single infinite loop
 * that blocks for `waitTimeSeconds(5)` on each iteration — a dedicated thread more directly expresses
 * this loop's intent (a background worker started exactly once at app bootstrap).
 *
 * Handler succeeds -> delete the message (ack). Handler fails (or no handler is registered) -> don't
 * delete it — SQS automatically redelivers it once the visibility timeout passes (at-least-once). The
 * EventHandler idempotency this repository requires (domain-events.md) is built on exactly this
 * redelivery guarantee.
 *
 * Start/stop is expressed with a single [SmartLifecycle], not the combination of Spring's framework
 * listener annotations + `DisposableBean` — in this repository, those listener annotations are the
 * marker for a Domain Event Handler (the harness's event-placement rule enforces `application/event/`
 * placement for them), so using the same annotation for a framework lifecycle callback would overload
 * its meaning. `SmartLifecycle.start()` is called right after the context comes up, and `stop()` is
 * called during Graceful Shutdown.
 */
@Component
class OutboxConsumer(
    private val sqsClient: SqsClient,
    private val registry: EventHandlerRegistry,
    private val sqsProperties: SqsProperties,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(OutboxConsumer::class.java)

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    // This is a singleton background loop started exactly once after app bootstrap finishes — it is
    // not created anew per request.
    override fun start() {
        running = true
        workerThread =
            Thread(::pollLoop, "outbox-consumer").apply {
                isDaemon = false
                start()
            }
    }

    // Stops the loop during Graceful Shutdown (when Spring closes the context and calls
    // SmartLifecycle.stop()). It waits for any in-flight ReceiveMessageRequest (up to waitTimeSeconds)
    // to finish before exiting — this applies graceful-shutdown.md's "the Scheduler explicitly sets a
    // shutdown wait" principle to a dedicated thread.
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
                            .queueUrl(sqsProperties.domainEventQueueUrl)
                            .maxNumberOfMessages(10)
                            .messageAttributeNames("eventType", "eventId")
                            .waitTimeSeconds(5)
                            .build(),
                    )
                result.messages().forEach { handle(it) }
            } catch (e: Exception) {
                if (running) {
                    logger.error("Failed to receive from SQS", e)
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun handle(message: Message) {
        val eventType = message.messageAttributes()["eventType"]?.stringValue()
        val eventId = message.messageAttributes()["eventId"]?.stringValue() ?: ""
        try {
            if (eventType == null) throw IllegalStateException("Missing eventType message attribute.")
            registry.dispatch(eventType, eventId, message.body())
            sqsClient.deleteMessage(
                DeleteMessageRequest
                    .builder()
                    .queueUrl(sqsProperties.domainEventQueueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build(),
            )
        } catch (e: Exception) {
            // Don't delete it — it will be received again and retried once the visibility timeout
            // passes.
            logger
                .atError()
                .addKeyValue("event_type", eventType)
                .setCause(e)
                .log("Failed to process event")
        }
    }

    companion object {
        private const val SHUTDOWN_JOIN_TIMEOUT_MS = 10_000L
    }
}
