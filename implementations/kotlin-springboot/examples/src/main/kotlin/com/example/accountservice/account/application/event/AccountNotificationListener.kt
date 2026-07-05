package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.AccountClosedEvent
import com.example.accountservice.account.domain.AccountCreatedEvent
import com.example.accountservice.account.domain.AccountReactivatedEvent
import com.example.accountservice.account.domain.AccountSuspendedEvent
import com.example.accountservice.account.domain.MoneyDepositedEvent
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.notification.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 계좌 도메인 이벤트가 발생하면 소유자에게 알림 이메일을 발송한다.
 *
 * ApplicationEventPublisher.publishEvent()는 기본적으로 이벤트를 발행한 서비스 메서드와
 * 같은 호출 스택에서 동기적으로 리스너를 실행한다. 따라서 알림 발송 실패가 계좌 커맨드
 * 자체를 실패시키지 않도록 각 핸들러 내부에서 예외를 잡아 로그만 남긴다.
 */
@Component
class AccountNotificationListener(private val notificationService: NotificationService) {

    private val logger = LoggerFactory.getLogger(AccountNotificationListener::class.java)

    @EventListener
    fun handleAccountCreated(event: AccountCreatedEvent) {
        runCatching {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "AccountCreated",
                recipient = event.email,
                subject = "[Account] 계좌가 개설되었습니다",
                body = "계좌(${event.accountId})가 개설되었습니다. 통화: ${event.currency}",
            )
        }.onFailure { logNotificationFailure("AccountCreated", event.accountId, it) }
    }

    @EventListener
    fun handleMoneyDeposited(event: MoneyDepositedEvent) {
        runCatching {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "MoneyDeposited",
                recipient = event.email,
                subject = "[Account] 입금이 완료되었습니다",
                body = "${event.amount.amount} ${event.amount.currency}이 입금되었습니다. " +
                    "입금 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
            )
        }.onFailure { logNotificationFailure("MoneyDeposited", event.accountId, it) }
    }

    @EventListener
    fun handleMoneyWithdrawn(event: MoneyWithdrawnEvent) {
        runCatching {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "MoneyWithdrawn",
                recipient = event.email,
                subject = "[Account] 출금이 완료되었습니다",
                body = "${event.amount.amount} ${event.amount.currency}이 출금되었습니다. " +
                    "출금 후 잔액: ${event.balanceAfter.amount} ${event.balanceAfter.currency}",
            )
        }.onFailure { logNotificationFailure("MoneyWithdrawn", event.accountId, it) }
    }

    @EventListener
    fun handleAccountSuspended(event: AccountSuspendedEvent) {
        runCatching {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "AccountSuspended",
                recipient = event.email,
                subject = "[Account] 계좌가 정지되었습니다",
                body = "계좌(${event.accountId})가 정지되었습니다.",
            )
        }.onFailure { logNotificationFailure("AccountSuspended", event.accountId, it) }
    }

    @EventListener
    fun handleAccountReactivated(event: AccountReactivatedEvent) {
        runCatching {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "AccountReactivated",
                recipient = event.email,
                subject = "[Account] 계좌가 재개되었습니다",
                body = "계좌(${event.accountId})가 재개되었습니다.",
            )
        }.onFailure { logNotificationFailure("AccountReactivated", event.accountId, it) }
    }

    @EventListener
    fun handleAccountClosed(event: AccountClosedEvent) {
        runCatching {
            notificationService.sendEmail(
                accountId = event.accountId,
                eventType = "AccountClosed",
                recipient = event.email,
                subject = "[Account] 계좌가 해지되었습니다",
                body = "계좌(${event.accountId})가 해지되었습니다.",
            )
        }.onFailure { logNotificationFailure("AccountClosed", event.accountId, it) }
    }

    private fun logNotificationFailure(eventType: String, accountId: String, error: Throwable) {
        logger.error("이메일 발송 실패: eventType={}, accountId={}", eventType, accountId, error)
    }
}
