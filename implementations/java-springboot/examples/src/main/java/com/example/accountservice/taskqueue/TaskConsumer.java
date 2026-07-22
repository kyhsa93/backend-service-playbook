package com.example.accountservice.taskqueue;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.example.accountservice.config.SqsProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Waits on the Task Queue (SQS FIFO) via long polling, and when a message arrives, looks up the
 * handler by {@code taskType} (MessageAttributes) among the injected {@link TaskHandler} list (each
 * domain's Task Controller) and invokes it — this repeats the same structure as {@code
 * outbox/OutboxConsumer}, but for the Task Queue. A Domain/Integration Event (a fact — "X
 * happened") and a Task (a command — "do X") are conceptually different, so they are kept on
 * separate Consumers/queues (see scheduling.md, domain-events.md).
 *
 * <p>Handler succeeds → delete the message (ack). Handler fails (or no handler is registered) → do
 * not delete — once SQS's visibility timeout elapses, it's automatically redelivered
 * (at-least-once). Each Task Controller/Command Service's idempotency assumes this redelivery (see
 * the 3 levels of idempotency in scheduling.md).
 *
 * <p>The background loop design has the same rationale as {@code outbox/OutboxConsumer} — hanging a
 * blocking loop that repeats long polling (up to waitTimeSeconds) on the {@code @Scheduled} thread
 * pool risks delaying other scheduled tasks, so start/stop is managed via a dedicated single-thread
 * {@link ExecutorService} + {@link SmartLifecycle}.
 */
@Component
public class TaskConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class);
    private static final String TASK_TYPE_ATTRIBUTE = "taskType";

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final Map<String, TaskHandler> handlers;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "task-consumer"));

    private volatile boolean running = false;

    public TaskConsumer(
            SqsClient sqsClient, SqsProperties sqsProperties, List<TaskHandler> taskHandlers) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.handlers =
                taskHandlers.stream()
                        .collect(Collectors.toMap(TaskHandler::taskType, Function.identity()));
    }

    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);
    }

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
        String queueUrl = sqsProperties.taskQueueUrl();
        while (running) {
            try {
                ReceiveMessageResponse response =
                        sqsClient.receiveMessage(
                                ReceiveMessageRequest.builder()
                                        .queueUrl(queueUrl)
                                        .maxNumberOfMessages(10)
                                        .messageAttributeNames(TASK_TYPE_ATTRIBUTE)
                                        .waitTimeSeconds(5)
                                        .build());
                for (Message message : response.messages()) {
                    handleMessage(queueUrl, message);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Failed to receive from Task Queue", e);
                    sleepBeforeRetry();
                }
            }
        }
    }

    private void handleMessage(String queueUrl, Message message) {
        MessageAttributeValue attribute = message.messageAttributes().get(TASK_TYPE_ATTRIBUTE);
        String taskType = attribute != null ? attribute.stringValue() : null;
        try {
            if (taskType == null) {
                throw new IllegalStateException("Missing taskType message attribute.");
            }
            TaskHandler handler = handlers.get(taskType);
            if (handler == null) {
                throw new IllegalStateException("No Task Handler registered for: " + taskType);
            }
            handler.handle(message.body());
            sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
        } catch (Exception e) {
            log.error("Failed to process task", kv("task_type", taskType), e);
            // Do not delete — it will be received again and retried after the visibility timeout.
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
