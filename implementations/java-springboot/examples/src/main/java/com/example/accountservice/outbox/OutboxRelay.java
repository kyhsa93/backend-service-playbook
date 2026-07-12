package com.example.accountservice.outbox;

import static net.logstash.logback.argument.StructuredArguments.kv;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Outbox 테이블에서 미처리(processed=false) 이벤트를 모두 읽어 이벤트 타입에 맞는
 * {@link OutboxEventHandler}로 라우팅한다.
 *
 * {@code @Scheduled} 폴링 대신, Command Service가 자신의 저장 트랜잭션이 커밋된 직후
 * 이 메서드를 동기적으로 한 번 호출하는 방식을 쓴다 — e2e 테스트에서 비동기 대기가 필요 없고,
 * 결정론적으로 동작한다. 테이블 전체를 매번 훑으므로 어떤 커맨드가 호출하든 다른 커맨드가 남긴
 * 미처리 이벤트까지 함께 재시도된다.
 *
 * 핸들러 실행(또는 그 실패)이 이 메서드를 호출한 원본 커맨드를 실패시켜서는 안 되므로,
 * 각 행은 개별적으로 try-catch하고 실패 시 processed를 갱신하지 않은 채 로그만 남긴다 —
 * 다음 호출(테이블 전체 재드레인)에서 다시 시도된다.
 *
 * 핸들러 실행 도중 새 outbox 행이 적재될 수 있다(예: {@code AccountSuspendedEventHandler}가
 * Domain Event를 처리하며 {@code account.suspended.v1} Integration Event를 같은 트랜잭션에
 * 적재하고, Card BC의 {@code OutboxEventHandler}가 그 eventType으로 자동 라우팅됨). 한 번의
 * 조회로는 이렇게 드레인 도중 새로 생긴 행을 놓치므로, 더 이상 진전이 없을 때까지 여러 패스로
 * 반복 드레인한다 — Domain Event → Integration Event → 다른 BC 수신까지 이 메서드 한 번의
 * 호출(= 원본 커맨드 하나) 안에서 완결되게 하기 위함이다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_PASSES = 10;

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final List<OutboxEventHandler> eventHandlers;

    @Transactional
    public void processPending() {
        Map<String, OutboxEventHandler> handlers = eventHandlers.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        // 이번 호출에서 이미 실패한 행은 다음 패스에서 재시도하지 않는다 — 실패는 다음
        // processPending() 호출의 몫이다. 매 패스마다 같은 실패를 반복하는 낭비를 막는다.
        Set<String> failedInThisRun = new HashSet<>();

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<OutboxEvent> pending = outboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc().stream()
                    .filter(event -> !failedInThisRun.contains(event.getEventId()))
                    .toList();
            if (pending.isEmpty()) {
                return;
            }

            int progressed = 0;
            for (OutboxEvent event : pending) {
                try {
                    OutboxEventHandler handler = handlers.get(event.getEventType());
                    if (handler != null) {
                        handler.handle(event.getPayload());
                    }
                    event.markProcessed();
                    outboxJpaRepository.save(event);
                    progressed++;
                } catch (Exception e) {
                    failedInThisRun.add(event.getEventId());
                    log.error("이벤트 처리 실패", kv("event_type", event.getEventType()), kv("event_id", event.getEventId()), e);
                }
            }
            // 이번 패스에서 아무 행도 처리하지 못했다면 더 진전될 여지가 없으므로 종료한다.
            if (progressed == 0) {
                return;
            }
        }
    }
}
