package com.example.accountservice.outbox;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.config.SqsProperties;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Waits on SQS via long polling ({@link ReceiveMessageRequest.Builder#waitTimeSeconds}), and when a
 * message arrives, routes it by {@code eventType} (MessageAttributes) through {@link
 * OutboxEventDispatcher} — whether it's a Domain Event Handler (application/event/) or an
 * Integration Event receiver, it goes through this single Consumer. {@link OutboxEventDispatcher}
 * owns the {@code eventType -> handler(s)} routing table (grouped from the same {@code
 * List<OutboxEventHandler>} injection Spring auto-collects across the classpath) and supports more
 * than one handler per eventType — see its javadoc for the "1:N" contract this depends on.
 *
 * <p>Handler(s) succeed → delete the message (ack). Any handler fails (or no handler is registered)
 * → do not delete — once SQS's visibility timeout elapses, it's automatically redelivered
 * (at-least-once). The EventHandler idempotency this storage requires
 * (docs/architecture/domain-events.md) is exactly what this redelivery assumes.
 *
 * <p><b>Background loop design</b>: {@code @Scheduled(fixedDelay)} suits "a short task that runs at
 * a fixed interval and finishes," but this loop must repeat a long receive-wait that blocks for up
 * to 5 seconds via {@code waitTimeSeconds(5)} — continuing to hang such a blocking task on the
 * {@code @Scheduled} thread pool risks delaying other scheduled tasks (OutboxPoller, etc). So the
 * loop is submitted to a dedicated single-thread {@link ExecutorService}, and start/stop is managed
 * via {@link SmartLifecycle} — {@code start()} only submits the thread and returns immediately, so
 * it doesn't block application bootstrap, and {@code stop()} waits for the in-progress {@code
 * ReceiveMessage} call to finish (up to the {@code awaitTermination} timeout) before shutting down
 * gracefully — the same principle as {@code server.shutdown: graceful}.
 */
@Component
public class OutboxConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxConsumer.class);
    private static final String EVENT_TYPE_ATTRIBUTE = "eventType";
    private static final String TRACEPARENT_ATTRIBUTE = "traceparent";

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final OutboxEventDispatcher dispatcher;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "outbox-consumer"));

    private volatile boolean running = false;

    public OutboxConsumer(
            SqsClient sqsClient,
            SqsProperties sqsProperties,
            OutboxEventDispatcher dispatcher,
            Tracer tracer,
            Propagator propagator) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    // A singleton background loop started exactly once when app bootstrap (ApplicationContext
    // refresh) completes — it is not created anew per request. executor.submit() returns
    // immediately, so it doesn't block the main thread.
    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);
    }

    // Stops the loop on Graceful Shutdown (ApplicationContext close). Waits for the in-progress
    // ReceiveMessage call (up to waitTimeSeconds) to finish before shutting down.
    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        String queueUrl = sqsProperties.domainEventQueueUrl();
        while (running) {
            try {
                ReceiveMessageResponse response =
                        sqsClient.receiveMessage(
                                ReceiveMessageRequest.builder()
                                        .queueUrl(queueUrl)
                                        .maxNumberOfMessages(10)
                                        .messageAttributeNames(
                                                EVENT_TYPE_ATTRIBUTE, TRACEPARENT_ATTRIBUTE)
                                        .waitTimeSeconds(5)
                                        .build());
                for (Message message : response.messages()) {
                    handleMessage(queueUrl, message);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Failed to receive from SQS", e);
                    sleepBeforeRetry();
                }
            }
        }
    }

    private void handleMessage(String queueUrl, Message message) {
        MessageAttributeValue attribute = message.messageAttributes().get(EVENT_TYPE_ATTRIBUTE);
        String eventType = attribute != null ? attribute.stringValue() : null;
        MessageAttributeValue traceparentAttribute =
                message.messageAttributes().get(TRACEPARENT_ATTRIBUTE);
        String traceparent =
                traceparentAttribute != null ? traceparentAttribute.stringValue() : null;
        // Continues the same trace the HTTP request that produced this event started
        // (observability.md) — Micrometer Tracing then populates traceId/spanId into MDC for the
        // scope below automatically, so every log line the handler emits (and any further Outbox
        // row it writes) carries it too.
        Span span = startSpan(traceparent);
        Tracer.SpanInScope scope = tracer.withSpan(span);
        try {
            if (eventType == null) {
                throw new IllegalStateException("Missing eventType message attribute.");
            }
            dispatcher.dispatch(eventType, message.body());
            sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
        } catch (Exception e) {
            log.error("Failed to process event", kv("event_type", eventType), e);
            // Do not delete — it will be received again and retried after the visibility timeout.
        } finally {
            scope.close();
            span.end();
        }
    }

    private Span startSpan(String traceparent) {
        Span.Builder builder =
                traceparent != null
                        ? propagator.extract(
                                Map.of("traceparent", traceparent),
                                (carrier, key) -> carrier.get(key))
                        : tracer.spanBuilder();
        return builder.name("outbox.consume").start();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
