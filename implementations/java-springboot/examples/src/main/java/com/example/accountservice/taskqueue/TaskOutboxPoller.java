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
 * task_outbox 테이블 → Task Queue(SQS FIFO) 발행만 담당한다 — {@code outbox/OutboxPoller}와 동일한 구조다. 어떤 {@link
 * TaskHandler}도 직접 호출하지 않는다 — 그건 {@link TaskConsumer}의 몫이다.
 *
 * <p>Domain/Integration Event 큐(domain-events, 표준 큐)와 달리 Task Queue는 FIFO 큐다 —
 * MessageGroupId/MessageDeduplicationId로 여러 인스턴스가 같은 Cron tick에 중복 적재해도 큐에는 1건만 들어가게
 * 한다(scheduling.md "Cron 다중 인스턴스 안전성"). Task Queue와 Domain Event 큐를 분리한 것도 이 개념적 차이(명령 vs 사실)
 * 때문이다(domain-events.md).
 */
@Component
@RequiredArgsConstructor
public class TaskOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(TaskOutboxPoller.class);
    private static final String TASK_TYPE_ATTRIBUTE = "taskType";

    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    // @Transactional이 필요한 이유는 outbox/OutboxPoller.poll()과 동일하다 — payload가 @Lob 컬럼이라
    // 트랜잭션 경계 밖에서 늦게 스트리밍하면 "Unable to access lob stream" 예외가 난다.
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
                // 발행에 성공한 즉시 processed=true로 표시한다 — 발행 실패 행은 processed=false로
                // 남겨 다음 tick에서 재시도한다. 같은 deduplicationId로 여러 번 발행을 시도해도
                // FIFO 큐의 dedup 윈도우(5분) 안에서는 실제로 1건만 전달된다.
                entry.markProcessed();
                taskOutboxJpaRepository.save(entry);
            } catch (Exception e) {
                log.error(
                        "Task Queue 발행 실패",
                        kv("task_type", entry.getTaskType()),
                        kv("task_id", entry.getTaskId()),
                        e);
            }
        }
    }
}
