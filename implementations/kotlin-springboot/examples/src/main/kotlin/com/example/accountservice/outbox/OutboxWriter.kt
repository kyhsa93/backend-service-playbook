package com.example.accountservice.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Aggregate가 수집한 Domain Event 목록을 Outbox 행으로 변환해 저장한다.
 *
 * [com.example.accountservice.account.infrastructure.persistence.AccountRepositoryImpl.save]가
 * Aggregate 저장과 같은 메서드 호출(=같은 트랜잭션) 안에서 이 클래스를 호출한다 — Aggregate 상태와
 * 이벤트가 원자적으로 커밋되거나 함께 롤백된다(dual-write 문제 회피).
 */
@Component
class OutboxWriter(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    fun saveAll(events: List<Any>) {
        if (events.isEmpty()) return
        outboxEventJpaRepository.saveAll(events.map { OutboxEvent.from(it, objectMapper) })
    }
}
