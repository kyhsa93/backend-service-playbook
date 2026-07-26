package com.example.accountservice.account.application.query

import com.example.accountservice.account.application.service.NlTransactionAnswerComposer
import com.example.accountservice.account.application.service.NlTransactionQueryTranslator
import com.example.accountservice.account.application.service.TransactionFilter
import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.Money
import com.example.accountservice.account.domain.Transaction
import com.example.accountservice.account.domain.TransactionFindQuery
import com.example.accountservice.account.domain.TransactionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime

class AskTransactionHistoryServiceTest {
    private val accountQuery = mockk<AccountQuery>()
    private val translator = mockk<NlTransactionQueryTranslator>()
    private val composer = mockk<NlTransactionAnswerComposer>()
    private val service = AskTransactionHistoryService(accountQuery, translator, composer)

    @Test
    fun `scopes the retrieval to the requester regardless of what the translated filter contains`() {
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        every {
            accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId, ownerId = "owner-1"))
        } returns (listOf(account) to 1L)
        every { translator.translate(any()) } returns
            TransactionFilter(TransactionType.WITHDRAWAL, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))
        every { accountQuery.findTransactions(any()) } returns (emptyList<Transaction>() to 0L)
        every { composer.compose(any(), any()) } returns "No matching transactions were found."

        service.ask(account.accountId, "owner-1", "How much did I withdraw in July?")

        // ownerId always comes from the authenticated requester (used to verify account ownership
        // up front), never from the translated filter — TransactionFilter has no ownerId field to
        // begin with, but this pins the intent explicitly.
        verify(exactly = 1) {
            accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId, ownerId = "owner-1"))
        }
        verify(exactly = 1) {
            accountQuery.findTransactions(
                TransactionFindQuery(
                    accountId = account.accountId,
                    page = 0,
                    take = 50,
                    type = TransactionType.WITHDRAWAL,
                    fromDate = LocalDate.of(2026, 7, 1),
                    toDate = LocalDate.of(2026, 7, 31),
                ),
            )
        }
    }

    @Test
    fun `composes the answer from the retrieved transactions and returns the match count`() {
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        every {
            accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId, ownerId = "owner-1"))
        } returns (listOf(account) to 1L)
        val transaction =
            Transaction.reconstitute(
                transactionId = "t1",
                accountId = account.accountId,
                type = TransactionType.DEPOSIT,
                amount = Money(1000, "KRW"),
                referenceId = null,
                createdAt = LocalDateTime.now(),
            )
        every { translator.translate(any()) } returns TransactionFilter()
        every { accountQuery.findTransactions(any()) } returns (listOf(transaction) to 1L)
        every { composer.compose(any(), any()) } returns "You deposited 1000 KRW."

        val result = service.ask(account.accountId, "owner-1", "How much did I deposit?")

        assertThat(result).isEqualTo(AskTransactionHistoryResult("You deposited 1000 KRW.", 1))
    }

    @Test
    fun `throws without calling the translator or composer when the account does not belong to the requester`() {
        every {
            accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = "account-1", ownerId = "owner-1"))
        } returns (emptyList<Account>() to 0L)

        assertThrows<AccountNotFoundException> { service.ask("account-1", "owner-1", "anything") }
        verify(exactly = 0) { translator.translate(any()) }
        verify(exactly = 0) { composer.compose(any(), any()) }
    }
}
