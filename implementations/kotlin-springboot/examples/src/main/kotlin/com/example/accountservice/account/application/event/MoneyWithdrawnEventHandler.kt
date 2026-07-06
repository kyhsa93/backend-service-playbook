package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.notification.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * MoneyWithdrawnEvent가 Outbox를 통해 전달되면 출금 완료 알림 이메일을 발송한다.
 *
 * 예외를 잡지 않고 그대로 던진다 — [com.example.accountservice.outbox.OutboxRelay]가 이를 잡아
 * 로그를 남기고 Outbox 행을 processed=false로 남겨 다음 호출에서 재시도한다(at-least-once 전달).
 */
@Component
class MoneyWithdrawnEventHandler(private val notificationService: NotificationService) {

    fun handle(event: MoneyWithdrawnEvent) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "MoneyWithdrawn",
            recipient = event.email,
            subject = "[Account] 출금이 완료되었습니다",
            body = "${event.amount.amount} ${event.amount.currency}이 출금되었습니다. " +
                "출금 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
        )
    }
}
