package com.example.accountservice.outbox

import com.example.accountservice.account.application.event.AccountClosedEventHandler
import com.example.accountservice.account.application.event.AccountCreatedEventHandler
import com.example.accountservice.account.application.event.AccountReactivatedEventHandler
import com.example.accountservice.account.application.event.AccountSuspendedEventHandler
import com.example.accountservice.account.application.event.MoneyDepositedEventHandler
import com.example.accountservice.account.application.event.MoneyWithdrawnEventHandler
import com.example.accountservice.account.application.integrationevent.AccountClosedIntegrationEventV1
import com.example.accountservice.account.application.integrationevent.AccountSuspendedIntegrationEventV1
import com.example.accountservice.account.domain.AccountClosedEvent
import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.account.domain.AccountReactivatedEvent
import com.example.accountservice.account.domain.AccountSuspendedEvent
import com.example.accountservice.account.domain.MoneyDepositedEvent
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.interfaces.integrationevent.AccountIntegrationEventController
import com.example.accountservice.card.interfaces.integrationevent.CardIntegrationEventController
import com.example.accountservice.payment.application.event.PaymentCancelledEventHandler
import com.example.accountservice.payment.application.event.PaymentCompletedEventHandler
import com.example.accountservice.payment.application.event.RefundApprovedEventHandler
import com.example.accountservice.payment.application.integrationevent.PaymentCancelledIntegrationEventV1
import com.example.accountservice.payment.application.integrationevent.PaymentCompletedIntegrationEventV1
import com.example.accountservice.payment.application.integrationevent.RefundApprovedIntegrationEventV1
import com.example.accountservice.payment.domain.PaymentCancelledEvent
import com.example.accountservice.payment.domain.PaymentCompletedEvent
import com.example.accountservice.payment.domain.RefundApprovedEvent
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
 *
 * 핸들러 실행 도중 새 Outbox 행이 적재될 수 있다(예: AccountSuspendedEventHandler가
 * account.suspended.v1 Integration Event를 적재) — 이를 반영해 더 이상 진전이 없을 때까지
 * 여러 패스로 반복 드레인한다. 이렇게 하면 Domain Event → Integration Event → 외부 BC(Card) 수신이
 * 한 커맨드 처리(= 한 번의 processPending 호출, 같은 트랜잭션) 안에서 완결된다.
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
    // Card BC의 Integration Event 수신부. outbox/는 어느 BC에도 속하지 않는 공유 인프라이므로
    // 이 파일이 Card를 참조하는 것 자체는 원칙 위반이 아니다(Account가 Card를 참조하지만 않으면 된다).
    private val cardIntegrationEventController: CardIntegrationEventController,
    // Payment/Refund Domain Event → Integration Event 변환 핸들러. Payment BC가 두 번째로 이
    // OutboxRelay를 공유한다(Account에 이어).
    private val paymentCompletedEventHandler: PaymentCompletedEventHandler,
    private val paymentCancelledEventHandler: PaymentCancelledEventHandler,
    private val refundApprovedEventHandler: RefundApprovedEventHandler,
    // Payment BC의 Integration Event 수신부 — Account가 Payment의 payment.completed.v1/
    // payment.cancelled.v1/refund.approved.v1을 구독해 실제 차감/보상 크레딧을 수행한다.
    private val accountIntegrationEventController: AccountIntegrationEventController,
) {
    private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)

    @Transactional
    fun processPending() {
        // 이번 호출에서 이미 실패한 행은 다음 패스에서 재시도하지 않는다 — 실패는 다음
        // processPending() 호출의 몫이다. 한 번 실패한 행(예: 알림 미가용)을 매 패스마다
        // 다시 시도하는 낭비 없이, 드레인 도중 새로 적재된 행만 이어서 처리한다.
        val failedInThisRun = mutableSetOf<String>()

        for (pass in 0 until MAX_PASSES) {
            val pending =
                outboxEventJpaRepository
                    .findByProcessedFalseOrderByCreatedAtAsc()
                    .filter { it.eventId !in failedInThisRun }
            if (pending.isEmpty()) return

            var progressed = 0
            for (row in pending) {
                runCatching { dispatch(row.eventType, row.eventId, row.payload) }
                    .onSuccess {
                        row.markProcessed()
                        progressed++
                    }.onFailure {
                        failedInThisRun += row.eventId
                        logger
                            .atError()
                            .addKeyValue("event_type", row.eventType)
                            .addKeyValue("event_id", row.eventId)
                            .setCause(it)
                            .log("이벤트 처리 실패")
                    }
            }
            // 이번 패스에서 아무 행도 처리하지 못했다면 더 진전될 여지가 없으므로 종료한다.
            if (progressed == 0) return
        }
    }

    private fun dispatch(
        eventType: String,
        eventId: String,
        payload: String,
    ) {
        when (eventType) {
            "AccountCreatedEvent" ->
                accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java), eventId)
            "MoneyDepositedEvent" ->
                moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java), eventId)
            "MoneyWithdrawnEvent" ->
                moneyWithdrawnEventHandler.handle(objectMapper.readValue(payload, MoneyWithdrawnEvent::class.java), eventId)
            "AccountSuspendedEvent" ->
                accountSuspendedEventHandler.handle(objectMapper.readValue(payload, AccountSuspendedEvent::class.java), eventId)
            "AccountReactivatedEvent" ->
                accountReactivatedEventHandler.handle(objectMapper.readValue(payload, AccountReactivatedEvent::class.java), eventId)
            "AccountClosedEvent" ->
                accountClosedEventHandler.handle(objectMapper.readValue(payload, AccountClosedEvent::class.java), eventId)
            AccountSuspendedIntegrationEventV1.EVENT_NAME -> {
                val event = objectMapper.readValue(payload, AccountSuspendedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountSuspended(event.accountId)
            }
            AccountClosedIntegrationEventV1.EVENT_NAME -> {
                val event = objectMapper.readValue(payload, AccountClosedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountClosed(event.accountId)
            }
            "PaymentCompletedEvent" ->
                paymentCompletedEventHandler.handle(objectMapper.readValue(payload, PaymentCompletedEvent::class.java))
            "PaymentCancelledEvent" ->
                paymentCancelledEventHandler.handle(objectMapper.readValue(payload, PaymentCancelledEvent::class.java))
            "RefundApprovedEvent" ->
                refundApprovedEventHandler.handle(objectMapper.readValue(payload, RefundApprovedEvent::class.java))
            PaymentCompletedIntegrationEventV1.EVENT_NAME -> {
                val event = objectMapper.readValue(payload, PaymentCompletedIntegrationEventV1::class.java)
                accountIntegrationEventController.onPaymentCompleted(event.paymentId, event.accountId, event.amount)
            }
            PaymentCancelledIntegrationEventV1.EVENT_NAME -> {
                val event = objectMapper.readValue(payload, PaymentCancelledIntegrationEventV1::class.java)
                accountIntegrationEventController.onPaymentCancelled(event.paymentId, event.accountId, event.amount)
            }
            RefundApprovedIntegrationEventV1.EVENT_NAME -> {
                val event = objectMapper.readValue(payload, RefundApprovedIntegrationEventV1::class.java)
                accountIntegrationEventController.onRefundApproved(event.refundId, event.accountId, event.amount)
            }
            else -> logger.warn("알 수 없는 이벤트 타입: {}", eventType)
        }
    }

    companion object {
        private const val MAX_PASSES = 10
    }
}
