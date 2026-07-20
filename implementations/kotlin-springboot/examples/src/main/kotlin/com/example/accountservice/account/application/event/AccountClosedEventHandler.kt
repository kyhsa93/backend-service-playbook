package com.example.accountservice.account.application.event

import com.example.accountservice.account.application.integrationevent.AccountClosedIntegrationEventV1
import com.example.accountservice.account.application.service.NotificationService
import com.example.accountservice.account.domain.AccountClosedEvent
import com.example.accountservice.outbox.OutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * AccountClosedEvent가 Outbox를 통해 전달되면 계좌 해지 알림 이메일을 발송하고,
 * 외부 BC(Card 등)에 알리는 Integration Event(account.closed.v1)를 Outbox에 적재한다.
 *
 * application/event/의 EventHandler는 [OutboxWriter]를 직접 사용할 수 있는 유일한 예외로,
 * 여기서 내부 Domain Event를 외부 BC용 Integration Event로 변환한다(Aggregate가 Integration
 * Event를 직접 만들지 않는다 — 변환 지점은 항상 EventHandler다).
 * 이 핸들러 자체가 [com.example.accountservice.outbox.OutboxConsumer]가 SQS에서 AccountClosedEvent를
 * 수신했을 때 호출되는 콜백이다 — 여기서 적재한 Integration Event는 같은 호출 안에서 즉시 처리되지
 * 않고, 다음 [com.example.accountservice.outbox.OutboxPoller] tick(최대 1초 후)에 SQS로 발행되어
 * 별도로 소비된다(비동기 전환으로 "같은 트랜잭션 안 즉시 재드레인"은 더 이상 성립하지 않는다).
 */
@Component
class AccountClosedEventHandler(
    private val notificationService: NotificationService,
    private val outboxWriter: OutboxWriter,
) {
    private val logger = LoggerFactory.getLogger(AccountClosedEventHandler::class.java)

    fun handle(
        event: AccountClosedEvent,
        eventId: String,
    ) {
        // 외부 BC(Card 등)에 알리는 Integration Event를 Outbox에 적재한다.
        outboxWriter.saveAll(
            listOf(AccountClosedIntegrationEventV1(event.accountId, event.closedAt.toString())),
        )

        // 알림은 best-effort다(정지 핸들러와 동일한 이유 — 실패해도 이 핸들러를 던지게 두면
        // 이미 적재한 Integration Event가 다음 재드레인 때 중복 적재된다). 알림 자체의 재시도는
        // 별도 outbox 행(sent_email 파이프라인)의 몫이다. eventId는 이 Outbox 행의 eventId다 —
        // NotificationService가 이를 키로 Level 2(Ledger) 중복 발송 방지를 적용한다.
        try {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "AccountClosed",
                sourceEventId = eventId,
                recipient = event.email,
                subject = "[Account] 계좌가 해지되었습니다",
                body = "계좌(${event.accountId})가 해지되었습니다.",
            )
        } catch (e: Exception) {
            logger
                .atError()
                .addKeyValue("account_id", event.accountId)
                .setCause(e)
                .log("해지 알림 발송 실패")
        }
    }
}
