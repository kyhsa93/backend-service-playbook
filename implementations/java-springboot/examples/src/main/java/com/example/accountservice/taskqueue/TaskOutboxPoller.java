package com.example.accountservice.taskqueue;

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
 * Responsible only for publishing from the task_outbox table → the Task Queue (SQS FIFO) — the same
 * structure as {@code outbox/OutboxPoller}. It never calls any {@link TaskHandler} directly —
 * that's {@link TaskConsumer}'s job.
 *
 * <p>Unlike the Domain/Integration Event queue (domain-events, a standard queue), the Task Queue is
 * a FIFO queue — MessageGroupId/MessageDeduplicationId ensure that even if multiple instances
 * duplicate-enqueue on the same Cron tick, only one entry lands in the queue (see "Cron safety with
 * multiple instances" in scheduling.md). This conceptual difference (command vs. fact) is also why
 * the Task Queue and Domain Event queue are kept separate (domain-events.md).
 */
@Component
@RequiredArgsConstructor
public class TaskOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(TaskOutboxPoller.class);
    private static final String TASK_TYPE_ATTRIBUTE = "taskType";

    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    // The reason @Transactional is needed is the same as outbox/OutboxPoller.poll() — payload is
    // an @Lob column, so streaming it lazily outside the transaction boundary causes an "Unable to
    // access lob stream" exception.
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<TaskOutboxEntry> pending =
                taskOutboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (TaskOutboxEntry entry : pending) {
            try {
                sqsClient.sendMessage(
                        SendMessageRequest.builder()
                                .queueUrl(sqsProperties.taskQueueUrl())
                                .messageBody(entry.getPayload())
                                .messageGroupId(entry.getGroupId())
                                .messageDeduplicationId(entry.getDeduplicationId())
                                .messageAttributes(
                                        Map.of(
                                                TASK_TYPE_ATTRIBUTE,
                                                MessageAttributeValue.builder()
                                                        .dataType("String")
                                                        .stringValue(entry.getTaskType())
                                                        .build()))
                                .build());
                // Mark processed=true as soon as publishing succeeds — a row whose publish failed
                // is left as processed=false and retried on the next tick. Even if the same
                // deduplicationId is published multiple times, only one message is actually
                // delivered within the FIFO queue's dedup window (5 minutes).
                entry.markProcessed();
                taskOutboxJpaRepository.save(entry);
            } catch (Exception e) {
                log.error(
                        "Failed to publish to Task Queue",
                        kv("task_type", entry.getTaskType()),
                        kv("task_id", entry.getTaskId()),
                        e);
            }
        }
    }
}
