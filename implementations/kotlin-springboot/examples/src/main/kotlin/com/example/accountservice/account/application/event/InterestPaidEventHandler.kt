package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.InterestPaidEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * InterestPaidEvent가 Outbox를 통해 전달되면 이자 지급 알림 이메일을 발송한다 — 다른 5개 Account
 * Domain Event Handler(MoneyDepositedEventHandler 등)와 동일한 경로다. `PayInterestService`가
 * 이자를 실제로 적립할 때(0원이 아닐 때만)만 이 이벤트가 발행되므로, "이자가 0원이라 스킵된 계좌"는
 * 이메일도 발송되지 않는다.
 *
 * 예외를 잡지 않고 그대로 던진다 — [com.example.accountservice.outbox.OutboxConsumer]가 이를 잡아
 * 로그를 남기고 Outbox 행을 processed=false로 남겨 다음 호출에서 재시도한다(at-least-once 전달).
 */
@Component
class InterestPaidEventHandler(
    private val notificationService: NotificationService,
) {
    fun handle(
        event: InterestPaidEvent,
        eventId: String,
    ) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "InterestPaid",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] 이자가 지급되었습니다",
            body =
                "${event.amount.amount} ${event.amount.currency}의 이자가 지급되었습니다. " +
                    "지급 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
        )
    }
}
