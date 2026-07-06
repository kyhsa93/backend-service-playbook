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

    private OutboxEvent toOutboxEvent(Object event) {
        try {
            return OutboxEvent.create(event.getClass().getSimpleName(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.getClass().getSimpleName(), e);
        }
    }
}
