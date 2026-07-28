package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TransactionTest {
    @Test
    fun `categorize returns a new instance with the category set and every other field unchanged`() {
        val transaction =
            Transaction.reconstitute(
                transactionId = "transaction-1",
                accountId = "account-1",
                type = TransactionType.WITHDRAWAL,
                amount = Money(5500, "KRW"),
                referenceId = null,
                createdAt = LocalDateTime.of(2026, 7, 28, 0, 0),
                merchantName = "Starbucks Gangnam",
                category = null,
            )

        val categorized = transaction.categorize(TransactionCategory.FOOD)

        assertThat(categorized.category).isEqualTo(TransactionCategory.FOOD)
        assertThat(categorized).isNotSameAs(transaction)
        assertThat(transaction.category).isNull()
        assertThat(categorized.transactionId).isEqualTo(transaction.transactionId)
        assertThat(categorized.merchantName).isEqualTo(transaction.merchantName)
        assertThat(categorized.amount).isEqualTo(transaction.amount)
        assertThat(categorized.createdAt).isEqualTo(transaction.createdAt)
    }
}
