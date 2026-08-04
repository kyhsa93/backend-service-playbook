package com.example.accountservice.account.application.event

import com.example.accountservice.account.domain.Money
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.domain.TransactionRepository
import com.example.accountservice.common.nowUtc
import com.example.accountservice.notification.application.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class DetectWithdrawalAnomalyEventHandlerTest {
    private val transactionRepository = mockk<TransactionRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val handler = DetectWithdrawalAnomalyEventHandler(transactionRepository, notificationService)

    private fun event(amount: Long) =
        MoneyWithdrawnEvent(
            accountId = "account-1",
            email = "owner-1@example.com",
            transactionId = "transaction-1",
            amount = Money(amount, "KRW"),
            balanceAfter = Money(0, "KRW"),
            createdAt = nowUtc(),
        )

    @Test
    fun `when the withdrawal is a statistical outlier then sends an alert email`() {
        every {
            transactionRepository.findRecentWithdrawalAmounts("account-1", "transaction-1", 30)
        } returns listOf(10000L, 12000L, 9000L, 11000L, 10500L)

        handler.handle(event(5_000_000L), "event-1")

        verify(exactly = 1) {
            notificationService.sendEmail(
                accountId = "account-1",
                eventType = "WithdrawalAnomalyDetected",
                sourceEventId = "event-1",
                recipient = "owner-1@example.com",
                subject = any(),
                body = any(),
            )
        }
    }

    @Test
    fun `when the withdrawal is within the normal range then sends no alert`() {
        every {
            transactionRepository.findRecentWithdrawalAmounts("account-1", "transaction-1", 30)
        } returns listOf(10000L, 12000L, 9000L, 11000L, 10500L)

        handler.handle(event(10800L), "event-1")

        verify(exactly = 0) { notificationService.sendEmail(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `when the history is too short then sends no alert regardless of amount`() {
        every {
            transactionRepository.findRecentWithdrawalAmounts("account-1", "transaction-1", 30)
        } returns listOf(10000L, 12000L)

        handler.handle(event(5_000_000L), "event-1")

        verify(exactly = 0) { notificationService.sendEmail(any(), any(), any(), any(), any(), any()) }
    }
}
