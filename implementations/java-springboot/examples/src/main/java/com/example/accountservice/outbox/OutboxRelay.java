package com.example.accountservice.outbox;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final List<OutboxEventHandler> eventHandlers;

    @Transactional
    public void processPending() {
        Map<String, OutboxEventHandler> handlers = eventHandlers.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        List<OutboxEvent> pending = outboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                OutboxEventHandler handler = handlers.get(event.getEventType());
                if (handler != null) {
                    handler.handle(event.getPayload());
                }
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error("이벤트 처리 실패: eventType={}, eventId={}", event.getEventType(), event.getEventId(), e);
            }
        }
    }
}
