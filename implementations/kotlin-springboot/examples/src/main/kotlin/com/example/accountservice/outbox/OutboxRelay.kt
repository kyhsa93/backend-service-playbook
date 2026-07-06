package com.example.accountservice.outbox

import com.example.accountservice.account.application.event.AccountClosedEventHandler
import com.example.accountservice.account.application.event.AccountCreatedEventHandler
import com.example.accountservice.account.application.event.AccountReactivatedEventHandler
import com.example.accountservice.account.application.event.AccountSuspendedEventHandler
import com.example.accountservice.account.application.event.MoneyDepositedEventHandler
import com.example.accountservice.account.application.event.MoneyWithdrawnEventHandler
import com.example.accountservice.account.domain.AccountClosedEvent
import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.account.domain.AccountReactivatedEvent
import com.example.accountservice.account.domain.AccountSuspendedEvent
import com.example.accountservice.account.domain.MoneyDepositedEvent
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Outbox 테이블에 쌓인 미처리(processed=false) 이벤트를 모두 꺼내 이벤트 타입에 맞는
 * `application/event/` Handler로 전달한다.
 *
 * `@Scheduled` 폴러가 아니다 — 각 Command Service가 Aggregate 저장 트랜잭션이 커밋된 직후
 * [processPending]을 동기적으로 한 번 호출한다. 테이블 전체를 드레인하므로 이번 커맨드가 남긴
 * 이벤트뿐 아니라 이전 호출에서 처리에 실패해 남아 있던 이벤트도 함께 재시도된다.
 *
 * 행 하나의 핸들러 실행이 실패해도 예외를 전파하지 않는다 — 로그만 남기고 해당 행은
 * processed=false로 남아 다음 호출에서 다시 시도된다(at-least-once 전달). 이미 처리된 다른
 * 행에는 영향이 없다.
 */
@Component
class OutboxRelay(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val accountCreatedEventHandler: AccountCreatedEventHandler,
    private val moneyDepositedEventHandler: MoneyDepositedEventHandler,
    private val moneyWithdrawnEventHandler: MoneyWithdrawnEventHandler,
    private val accountSuspendedEventHandler: AccountSuspendedEventHandler,
    private val accountReactivatedEventHandler: AccountReactivatedEventHandler,
    private val accountClosedEventHandler: AccountClosedEventHandler,
) {
    private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)

    @Transactional
    fun processPending() {
        val pending = outboxEventJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        for (row in pending) {
            runCatching { dispatch(row.eventType, row.payload) }
                .onSuccess { row.markProcessed() }
                .onFailure { logger.error("이벤트 처리 실패: eventType={}, eventId={}", row.eventType, row.eventId, it) }
        }
    }

    private fun dispatch(eventType: String, payload: String) {
        when (eventType) {
            "AccountCreatedEvent" ->
                accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java))
            "MoneyDepositedEvent" ->
                moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java))
            "MoneyWithdrawnEvent" ->
                moneyWithdrawnEventHandler.handle(objectMapper.readValue(payload, MoneyWithdrawnEvent::class.java))
            "AccountSuspendedEvent" ->
                accountSuspendedEventHandler.handle(objectMapper.readValue(payload, AccountSuspendedEvent::class.java))
            "AccountReactivatedEvent" ->
                accountReactivatedEventHandler.handle(objectMapper.readValue(payload, AccountReactivatedEvent::class.java))
            "AccountClosedEvent" ->
                accountClosedEventHandler.handle(objectMapper.readValue(payload, AccountClosedEvent::class.java))
            else -> logger.warn("알 수 없는 이벤트 타입: {}", eventType)
        }
    }
}
