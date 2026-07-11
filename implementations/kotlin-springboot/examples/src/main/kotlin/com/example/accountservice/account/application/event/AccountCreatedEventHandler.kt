package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.account.application.service.NotificationService
import org.springframework.stereotype.Component

/**
 * AccountCreatedEvent가 Outbox를 통해 전달되면 계좌 개설 알림 이메일을 발송한다.
 *
 * 예외를 잡지 않고 그대로 던진다 — [com.example.accountservice.outbox.OutboxRelay]가 이를 잡아
 * 로그를 남기고 Outbox 행을 processed=false로 남겨 다음 호출에서 재시도한다(at-least-once 전달).
 */
@Component
class AccountCreatedEventHandler(private val notificationService: NotificationService) {

    fun handle(event: AccountCreatedEvent) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountCreated",
            recipient = event.email,
            subject = "[Account] 계좌가 개설되었습니다",
            body = "계좌(${event.accountId})가 개설되었습니다. 통화: ${event.currency}",
        )
    }
}
