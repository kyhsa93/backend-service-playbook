package com.example.accountservice.account.application.event

import com.example.accountservice.account.application.service.NotificationService
import com.example.accountservice.account.domain.AccountReactivatedEvent
import org.springframework.stereotype.Component

/**
 * AccountReactivatedEvent가 Outbox를 통해 전달되면 계좌 재개 알림 이메일을 발송한다.
 *
 * 예외를 잡지 않고 그대로 던진다 — [com.example.accountservice.outbox.OutboxConsumer]가 이를 잡아
 * 로그를 남기고 Outbox 행을 processed=false로 남겨 다음 호출에서 재시도한다(at-least-once 전달).
 *
 * [eventId]는 이 이벤트가 저장된 Outbox 행의 `eventId`다 — [NotificationService]가 이를 키로
 * Level 2(Ledger) 중복 발송 방지를 적용한다.
 */
@Component
class AccountReactivatedEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: AccountReactivatedEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountReactivated",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] 계좌가 재개되었습니다",
            body = "계좌(${event.accountId})가 재개되었습니다.",
        )
    }
}
