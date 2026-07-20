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
 * Outbox 테이블 → SQS 발행만 담당한다("DB에 쌓인 이벤트를 큐로 실어 나른다"). 어떤 {@link OutboxEventHandler}도 직접 호출하지 않는다 —
 * 그건 {@link OutboxConsumer}의 몫이다.
 *
 * <p>Command Service는 이 클래스를 전혀 참조하지 않는다. {@code @Scheduled}로 독립적으로 주기 실행되는 것 자체가 "저장 직후 같은 프로세스
 * 안에서 동기 드레인"을 제거하는 핵심이다 — Command가 저장을 커밋하고 응답을 반환한 뒤에도, 이 이벤트가 언제 큐로 나가는지는 다음 tick(최대 1초 뒤)까지 알 수
 * 없다.
 *
 * <p>{@code processed=true}는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는 뜻이다 — 이후의 재시도/at-least-once 보장은
 * outbox 테이블이 아니라 SQS의 visibility timeout + DLQ가 담당한다 (docs/architecture/domain-events.md 참고).
 *
 * <p>{@code fixedDelay}는 이전 실행이 끝난 뒤부터 다음 실행까지의 간격을 보장한다 — Spring의 기본 스케줄러는 단일 스레드이므로 이전 tick의 드레인이
 * 끝나기 전에 다음 tick이 겹쳐 실행되지 않는다(nestjs의 {@code isPolling} 플래그와 동일한 효과를 프레임워크가 대신 보장한다).
 */
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    // @Transactional이 필요한 이유: findByProcessedFalseOrderByCreatedAtAsc()가 로드하는
    // OutboxEvent.payload는 @Lob 컬럼이다 — 이 메서드 자체가 트랜잭션 경계가 아니면 조회에 쓰인
    // 세션/커넥션이 이미 반환된 뒤 아래 for 루프에서 event.getPayload()를 늦게 스트리밍하려다
    // "Unable to access lob stream" 예외가 난다(예전 OutboxRelay.processPending()도 동일한
    // 이유로 @Transactional이 붙어 있었다 — 동기 드레인을 비동기로 바꾸면서도 이 트랜잭션 경계
    // 필요성은 그대로 남는다).
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
                // 발행에 성공한 즉시 processed=true로 표시한다 — 발행 실패 행은 processed=false로
                // 남겨 다음 tick에서 재시도한다.
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error(
                        "SQS 발행 실패",
                        kv("event_type", event.getEventType()),
                        kv("event_id", event.getEventId()),
                        e);
            }
        }
    }
}
