package com.example.accountservice.outbox;

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
 * SQS를 long polling({@link ReceiveMessageRequest.Builder#waitTimeSeconds}) 으로 수신 대기하다가 메시지를 받으면
 * {@code eventType}(MessageAttributes)으로 주입받은 {@link OutboxEventHandler} 목록에서 핸들러를 찾아 호출한다 — Domain
 * Event Handler(application/event/)든 Integration Event 수신부든 이 하나의 Consumer를 거친다. 핸들러 등록은 Spring이
 * classpath 전체에서 {@code OutboxEventHandler} 구현체를 자동 수집한 {@code List<OutboxEventHandler>} 주입 그대로
 * 재사용한다 — {@code OutboxRelay}가 하던 것과 동일하다.
 *
 * <p>핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지 않는다 — SQS의 visibility timeout이 지나면 자동
 * 재전달된다(at-least-once). 이 저장소가 요구하는 EventHandler 멱등성 (docs/architecture/domain-events.md)이 바로 이
 * 재전달을 전제한다.
 *
 * <p><b>백그라운드 루프 설계</b>: {@code @Scheduled(fixedDelay)}는 "고정 간격으로 짧게 실행되고 끝나는 작업"에 적합하지만, 이 루프는
 * {@code waitTimeSeconds(5)}로 최대 5초씩 블로킹하는 긴 수신 대기를 반복해야 한다 — {@code @Scheduled} 스레드 풀에 이런 블로킹 작업을
 * 계속 물려두면 다른 스케줄 작업(OutboxPoller 등)의 실행을 지연시킬 위험이 있다. 그래서 전용 단일 스레드 {@link ExecutorService}에 루프를
 * 제출하고, {@link SmartLifecycle}로 시작/종료를 관리한다 — {@code start()}는 스레드 제출만 하고 즉시 반환하므로 애플리케이션 부트스트랩을 막지
 * 않고, {@code stop()}은 진행 중인 {@code ReceiveMessage} 호출이 끝날 때까지 기다린 뒤(최대 {@code awaitTermination}
 * 타임아웃) graceful하게 종료한다 — {@code server.shutdown: graceful}과 동일한 원칙이다.
 */
@Component
public class OutboxConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxConsumer.class);
    private static final String EVENT_TYPE_ATTRIBUTE = "eventType";

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final Map<String, OutboxEventHandler> handlers;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "outbox-consumer"));

    private volatile boolean running = false;

    public OutboxConsumer(
            SqsClient sqsClient,
            SqsProperties sqsProperties,
            List<OutboxEventHandler> eventHandlers) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.handlers =
                eventHandlers.stream()
                        .collect(
                                Collectors.toMap(
                                        OutboxEventHandler::eventType, Function.identity()));
    }

    // 앱 부트스트랩(ApplicationContext refresh) 완료 시 단 한 번 시작되는 싱글턴 백그라운드 루프다 —
    // 요청마다 새로 만들어지지 않는다. executor.submit()은 즉시 반환하므로 메인 스레드를 막지 않는다.
    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);
    }

    // Graceful Shutdown(ApplicationContext 종료) 시 루프를 멈춘다. 진행 중인 ReceiveMessage 호출(최대
    // waitTimeSeconds)은 끝까지 기다린 뒤 종료한다.
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
                                        .messageAttributeNames(EVENT_TYPE_ATTRIBUTE)
                                        .waitTimeSeconds(5)
                                        .build());
                for (Message message : response.messages()) {
                    handleMessage(queueUrl, message);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("SQS 수신 실패", e);
                    sleepBeforeRetry();
                }
            }
        }
    }

    private void handleMessage(String queueUrl, Message message) {
        MessageAttributeValue attribute = message.messageAttributes().get(EVENT_TYPE_ATTRIBUTE);
        String eventType = attribute != null ? attribute.stringValue() : null;
        try {
            if (eventType == null) {
                throw new IllegalStateException("eventType 메시지 속성이 없습니다.");
            }
            OutboxEventHandler handler = handlers.get(eventType);
            if (handler == null) {
                throw new IllegalStateException("등록된 핸들러가 없습니다: " + eventType);
            }
            handler.handle(message.body());
            sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
        } catch (Exception e) {
            log.error("이벤트 처리 실패", kv("event_type", eventType), e);
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
