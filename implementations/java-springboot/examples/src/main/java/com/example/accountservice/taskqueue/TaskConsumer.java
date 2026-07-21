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
 * Task Queue(SQS FIFO)를 long polling으로 수신 대기하다가 메시지를 받으면 {@code taskType} (MessageAttributes)으로
 * 주입받은 {@link TaskHandler} 목록(각 도메인의 Task Controller)에서 핸들러를 찾아 호출한다 — {@code
 * outbox/OutboxConsumer}와 동일한 구조를 Task Queue에 대해 반복한다. Domain/ Integration Event(사실 — "X가 일어났다")와
 * Task(명령 — "X를 수행하라")는 개념적으로 다르므로 별도의 Consumer/큐로 분리한다(scheduling.md, domain-events.md).
 *
 * <p>핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지 않는다 — SQS의 visibility timeout이 지나면 자동
 * 재전달된다(at-least-once). 각 Task Controller/Command Service의 멱등성이 이 재전달을 전제한다(scheduling.md의 멱등성
 * 3단계).
 *
 * <p>백그라운드 루프 설계는 {@code outbox/OutboxConsumer}와 동일한 이유다 — long polling(최대 waitTimeSeconds)을 반복하는
 * 블로킹 루프를 {@code @Scheduled} 스레드 풀에 물려두면 다른 스케줄 작업의 실행을 지연시킬 위험이 있어, 전용 단일 스레드 {@link
 * ExecutorService} + {@link SmartLifecycle}로 시작/종료를 관리한다.
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
                    log.error("Task Queue 수신 실패", e);
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
                throw new IllegalStateException("taskType 메시지 속성이 없습니다.");
            }
            TaskHandler handler = handlers.get(taskType);
            if (handler == null) {
                throw new IllegalStateException("등록된 Task Handler가 없습니다: " + taskType);
            }
            handler.handle(message.body());
            sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
        } catch (Exception e) {
            log.error("Task 처리 실패", kv("task_type", taskType), e);
            // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
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
