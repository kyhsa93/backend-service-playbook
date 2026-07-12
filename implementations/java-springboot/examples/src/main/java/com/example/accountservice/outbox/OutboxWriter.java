package com.example.accountservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggregate가 수집한 도메인 이벤트를 Outbox 테이블에 저장한다.
 * {@link com.example.accountservice.account.infrastructure.persistence.AccountRepositoryImpl#save}가
 * Aggregate 저장과 같은 물리 트랜잭션 안에서 호출해, 이벤트 발행이 Aggregate 상태 변경과 원자적으로 커밋되도록 한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final ObjectMapper objectMapper;

    public void saveAll(List<Object> events) {
        if (events.isEmpty()) {
            return;
        }
        List<OutboxEvent> outboxEvents = events.stream().map(this::toOutboxEvent).toList();
        outboxJpaRepository.saveAll(outboxEvents);
    }

    /**
     * 명시적 eventType으로 이벤트 한 건을 Outbox에 적재한다. 외부 BC에 공개하는
     * Integration Event는 클래스명 대신 버전이 명시된 공개 계약명(예: {@code account.suspended.v1})을
     * eventType으로 써야 하므로, 도메인 이벤트용 {@link #saveAll(List)}과 달리 타입을 직접 받는다.
     * Domain Event를 수신한 application/event 핸들러가 Integration Event로 변환할 때 호출한다.
     */
    public void save(String eventType, Object payload) {
        try {
            outboxJpaRepository.save(OutboxEvent.create(eventType, objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + eventType, e);
        }
    }

    private OutboxEvent toOutboxEvent(Object event) {
        try {
            return OutboxEvent.create(event.getClass().getSimpleName(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.getClass().getSimpleName(), e);
        }
    }
}
