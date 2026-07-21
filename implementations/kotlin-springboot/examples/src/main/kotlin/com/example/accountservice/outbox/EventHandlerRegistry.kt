package com.example.accountservice.outbox

import com.example.accountservice.account.application.event.AccountClosedEventHandler
import com.example.accountservice.account.application.event.AccountCreatedEventHandler
import com.example.accountservice.account.application.event.AccountReactivatedEventHandler
import com.example.accountservice.account.application.event.AccountSuspendedEventHandler
import com.example.accountservice.account.application.event.InterestPaidEventHandler
import com.example.accountservice.account.application.event.MoneyDepositedEventHandler
import com.example.accountservice.account.application.event.MoneyWithdrawnEventHandler
import com.example.accountservice.account.application.integrationevent.AccountClosedIntegrationEventV1
import com.example.accountservice.account.application.integrationevent.AccountSuspendedIntegrationEventV1
import com.example.accountservice.account.domain.AccountClosedEvent
import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.account.domain.AccountReactivatedEvent
import com.example.accountservice.account.domain.AccountSuspendedEvent
import com.example.accountservice.account.domain.InterestPaidEvent
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

/**
 * eventType(Outbox 행의 `eventType` 컬럼 = SQS `MessageAttributes.eventType`) → 핸들러 함수
 * 매핑. [OutboxConsumer]가 SQS에서 메시지를 수신했을 때 이 레지스트리로 라우팅한다 — Domain Event
 * Handler(`application/event/`)든 Integration Event Controller(`interfaces/integrationevent/`)든
 * 이 하나의 레지스트리를 거친다.
 *
 * 예전 `OutboxRelay.dispatch()`의 `when(eventType)` 하드코딩 분기를 대체한다. 차이는 라우팅
 * 테이블을 매번 `when`으로 평가하는 대신, 생성자 실행 시점(Spring이 이미 각 Handler/Controller를
 * `@Component`로 자동 수집해 주입한 뒤)에 `Map<eventType, (eventId, payload) -> Unit>` 하나로 미리
 * 구성해 둔다는 점뿐이다 — nestjs의 `EventHandlerRegistry.register()`처럼 각 도메인 모듈이
 * `onModuleInit()`에서 별도로 호출해 채우는 구조가 아니다(이 저장소는 도메인별 모듈 등록 단계가
 * 없는 단일 Spring 컨텍스트이므로 그렇게 나눌 필요가 없다 — 기존 `OutboxRelay`도 항상 계좌/결제
 * 도메인 핸들러를 전부 한 곳에서 알고 있었다).
 *
 * Account 7개 Domain Event Handler는 Outbox 행의 `eventId`를 그대로 전달받는다 — 이 값이
 * `NotificationService`의 Level 2(Ledger) 중복 발송 방지 키(`sourceEventId`)로 쓰인다
 * (domain-events.md). Payment/Refund Domain Event Handler와 Integration Event Controller는
 * `eventId`를 쓰지 않으므로 람다에서 무시한다.
 */
@Component
class EventHandlerRegistry(
    private val objectMapper: ObjectMapper,
    private val accountCreatedEventHandler: AccountCreatedEventHandler,
    private val moneyDepositedEventHandler: MoneyDepositedEventHandler,
    private val moneyWithdrawnEventHandler: MoneyWithdrawnEventHandler,
    private val accountSuspendedEventHandler: AccountSuspendedEventHandler,
    private val accountReactivatedEventHandler: AccountReactivatedEventHandler,
    private val accountClosedEventHandler: AccountClosedEventHandler,
    private val interestPaidEventHandler: InterestPaidEventHandler,
    // Card BC의 Integration Event 수신부. outbox/는 어느 BC에도 속하지 않는 공유 인프라이므로
    // 이 파일이 Card를 참조하는 것 자체는 원칙 위반이 아니다(Account가 Card를 참조하지만 않으면 된다).
    private val cardIntegrationEventController: CardIntegrationEventController,
    // Payment/Refund Domain Event → Integration Event 변환 핸들러.
    private val paymentCompletedEventHandler: PaymentCompletedEventHandler,
    private val paymentCancelledEventHandler: PaymentCancelledEventHandler,
    private val refundApprovedEventHandler: RefundApprovedEventHandler,
    // Payment BC의 Integration Event 수신부 — Account가 Payment의 payment.completed.v1/
    // payment.cancelled.v1/refund.approved.v1을 구독해 실제 차감/보상 크레딧을 수행한다.
    private val accountIntegrationEventController: AccountIntegrationEventController,
) {
    private val logger = LoggerFactory.getLogger(EventHandlerRegistry::class.java)

    private val handlers: Map<String, (eventId: String, payload: String) -> Unit> =
        mapOf(
            "AccountCreatedEvent" to { eventId, payload ->
                accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java), eventId)
            },
            "MoneyDepositedEvent" to { eventId, payload ->
                moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java), eventId)
            },
            "MoneyWithdrawnEvent" to { eventId, payload ->
                moneyWithdrawnEventHandler.handle(objectMapper.readValue(payload, MoneyWithdrawnEvent::class.java), eventId)
            },
            "AccountSuspendedEvent" to { eventId, payload ->
                accountSuspendedEventHandler.handle(objectMapper.readValue(payload, AccountSuspendedEvent::class.java), eventId)
            },
            "AccountReactivatedEvent" to { eventId, payload ->
                accountReactivatedEventHandler.handle(objectMapper.readValue(payload, AccountReactivatedEvent::class.java), eventId)
            },
            "AccountClosedEvent" to { eventId, payload ->
                accountClosedEventHandler.handle(objectMapper.readValue(payload, AccountClosedEvent::class.java), eventId)
            },
            "InterestPaidEvent" to { eventId, payload ->
                interestPaidEventHandler.handle(objectMapper.readValue(payload, InterestPaidEvent::class.java), eventId)
            },
            AccountSuspendedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, AccountSuspendedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountSuspended(event.accountId)
            },
            AccountClosedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, AccountClosedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountClosed(event.accountId)
            },
            "PaymentCompletedEvent" to { _, payload ->
                paymentCompletedEventHandler.handle(objectMapper.readValue(payload, PaymentCompletedEvent::class.java))
            },
            "PaymentCancelledEvent" to { _, payload ->
                paymentCancelledEventHandler.handle(objectMapper.readValue(payload, PaymentCancelledEvent::class.java))
            },
            "RefundApprovedEvent" to { _, payload ->
                refundApprovedEventHandler.handle(objectMapper.readValue(payload, RefundApprovedEvent::class.java))
            },
            PaymentCompletedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, PaymentCompletedIntegrationEventV1::class.java)
                accountIntegrationEventController.onPaymentCompleted(event.paymentId, event.accountId, event.amount)
            },
            PaymentCancelledIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, PaymentCancelledIntegrationEventV1::class.java)
                accountIntegrationEventController.onPaymentCancelled(event.paymentId, event.accountId, event.amount)
            },
            RefundApprovedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, RefundApprovedIntegrationEventV1::class.java)
                accountIntegrationEventController.onRefundApproved(event.refundId, event.accountId, event.amount)
            },
        )

    /** 등록된 eventType 집합 — 진단/테스트 용도(어떤 이벤트가 라우팅 가능한지 확인). */
    fun registeredEventTypes(): Set<String> = handlers.keys

    /**
     * [OutboxConsumer]가 SQS 메시지 하나를 수신할 때마다 호출한다. 등록된 핸들러가 없으면 경고만
     * 남기고 조용히 반환한다(예외를 던지지 않는다 — 알 수 없는 이벤트 타입을 무한 재시도할 이유가
     * 없다). 핸들러가 예외를 던지면 그대로 전파해 [OutboxConsumer]가 메시지를 삭제하지 않고
     * SQS의 재전달(at-least-once)에 맡기게 한다.
     */
    fun dispatch(
        eventType: String,
        eventId: String,
        payload: String,
    ) {
        val handler = handlers[eventType]
        if (handler == null) {
            logger.warn("알 수 없는 이벤트 타입: {}", eventType)
            return
        }
        handler(eventId, payload)
    }
}
