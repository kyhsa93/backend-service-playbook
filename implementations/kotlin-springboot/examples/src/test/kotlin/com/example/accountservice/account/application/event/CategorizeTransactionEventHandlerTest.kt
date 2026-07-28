package com.example.accountservice.account.application.event

import com.example.accountservice.account.application.service.TransactionAutoCategorizer
import com.example.accountservice.account.domain.Money
import com.example.accountservice.account.domain.MoneyWithdrawnEvent
import com.example.accountservice.account.domain.Transaction
import com.example.accountservice.account.domain.TransactionCategory
import com.example.accountservice.account.domain.TransactionRepository
import com.example.accountservice.account.domain.TransactionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CategorizeTransactionEventHandlerTest {
    private val transactionAutoCategorizer = mockk<TransactionAutoCategorizer>()
    private val transactionRepository = mockk<TransactionRepository>(relaxed = true)
    private val handler = CategorizeTransactionEventHandler(transactionAutoCategorizer, transactionRepository)

    private val transaction =
        Transaction.reconstitute(
            transactionId = "transaction-1",
            accountId = "account-1",
            type = TransactionType.WITHDRAWAL,
            amount = Money(5500, "KRW"),
            referenceId = null,
            createdAt = LocalDateTime.now(),
            merchantName = "Starbucks Gangnam",
            category = null,
        )

    private fun event(merchantName: String?) =
        MoneyWithdrawnEvent(
            accountId = "account-1",
            email = "owner-1@example.com",
            transactionId = "transaction-1",
            amount = Money(5500, "KRW"),
            balanceAfter = Money(4500, "KRW"),
            createdAt = LocalDateTime.now(),
            merchantName = merchantName,
        )

    @Test
    fun `when the event has a merchantName then categorizes and saves it`() {
        every { transactionRepository.findTransaction("transaction-1") } returns transaction
        every { transactionAutoCategorizer.categorize("Starbucks Gangnam", 5500) } returns TransactionCategory.FOOD

        handler.handle(event("Starbucks Gangnam"))

        verify(exactly = 1) { transactionAutoCategorizer.categorize("Starbucks Gangnam", 5500) }
        val saved = slot<Transaction>()
        verify(exactly = 1) { transactionRepository.saveTransaction(capture(saved)) }
        assertThat(saved.captured.category).isEqualTo(TransactionCategory.FOOD)
    }

    @Test
    fun `when the event has no merchantName then skips categorization entirely`() {
        handler.handle(event(null))

        verify(exactly = 0) { transactionRepository.findTransaction(any()) }
        verify(exactly = 0) { transactionAutoCategorizer.categorize(any(), any()) }
        verify(exactly = 0) { transactionRepository.saveTransaction(any()) }
    }

    @Test
    fun `when the transaction no longer exists then skips categorization without throwing`() {
        every { transactionRepository.findTransaction("transaction-1") } returns null

        handler.handle(event("Starbucks Gangnam"))

        verify(exactly = 0) { transactionAutoCategorizer.categorize(any(), any()) }
        verify(exactly = 0) { transactionRepository.saveTransaction(any()) }
    }
}
