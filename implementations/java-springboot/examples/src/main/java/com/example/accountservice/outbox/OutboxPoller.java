package com.example.accountservice.outbox;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.config.SqsProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Responsible only for publishing from the Outbox table → SQS ("carries events accumulated in the
 * DB over to the queue"). It never calls any {@link OutboxEventHandler} directly — that's {@link
 * OutboxConsumer}'s job.
 *
 * <p>The Command Service never references this class at all. Running independently and periodically
 * via {@code @Scheduled} is itself the key to eliminating "synchronous draining in the same
 * process, right after saving" — even after a Command commits the save and returns a response,
 * exactly when this event goes out to the queue is unknown until the next tick (up to 1 second
 * later).
 *
 * <p>{@code processed=true} no longer means "the handler finished processing" — it means "delivery
 * to SQS finished." From then on, retry/at-least-once guarantees are the responsibility of SQS's
 * visibility timeout + DLQ, not the outbox table (see docs/architecture/domain-events.md).
 *
 * <p>{@code fixedDelay} guarantees the interval from when the previous execution ends to when the
 * next one starts — since Spring's default scheduler is single-threaded, the next tick never
 * overlaps with the previous tick's drain before it finishes (the framework guarantees the same
 * effect nestjs achieves with its {@code isPolling} flag).
 */
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    // Why @Transactional is needed: OutboxEvent.payload, loaded by
    // findByProcessedFalseOrderByCreatedAtAsc(), is an @Lob column — if this method itself isn't a
    // transaction boundary, the session/connection used for the query is already returned by the
    // time the for loop below tries to lazily stream event.getPayload(), causing an "Unable to
    // access lob stream" exception (the old OutboxRelay.processPending() also had @Transactional
    // for the same reason — even after switching from synchronous to asynchronous draining, this
    // need for a transaction boundary remains unchanged).
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> pending = outboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                sqsClient.sendMessage(
                        SendMessageRequest.builder()
                                .queueUrl(sqsProperties.domainEventQueueUrl())
                                .messageBody(event.getPayload())
                                .messageAttributes(
                                        Map.of(
                                                "eventType",
                                                MessageAttributeValue.builder()
                                                        .dataType("String")
                                                        .stringValue(event.getEventType())
                                                        .build()))
                                .build());
                // Mark processed=true as soon as publishing succeeds — a row whose publish failed
                // is left as processed=false and retried on the next tick.
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error(
                        "Failed to publish to SQS",
                        kv("event_type", event.getEventType()),
                        kv("event_id", event.getEventId()),
                        e);
            }
        }
    }
}
