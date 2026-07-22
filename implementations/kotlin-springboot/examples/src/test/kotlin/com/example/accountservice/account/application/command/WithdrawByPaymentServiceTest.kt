package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.TransactionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The reaction use case for the Payment BC's payment.completed.v1 event — the crux of this test is
 * whether Level 2 (Ledger) idempotency is scoped by the (referenceId, type) combination. If it were
 * checked by referenceId alone, then when a completed payment (WITHDRAWAL) and its compensating credit
 * (DEPOSIT, see DepositByPaymentServiceTest) share the same paymentId, the compensating credit would be
 * incorrectly skipped as "already processed" — this bug was actually encountered and fixed in the
 * nestjs reference implementation.
 */
class WithdrawByPaymentServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = WithdrawByPaymentService(accountRepository)

    @Test
    fun `withdraws and saves when the referenceId has not yet been processed`() {
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        account.deposit(1000)
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL) } returns false
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId))
        } returns (listOf(account) to 1L)

        service.withdraw(WithdrawByPaymentCommand(accountId = account.accountId, amount = 500, referenceId = "payment-1"))

        assertThat(account.balance.amount).isEqualTo(500)
        verify(exactly = 1) { accountRepository.saveAccount(account) }
    }

    @Test
    fun `silently ignores it when the same referenceId+WITHDRAWAL has already been processed (idempotent)`() {
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL) } returns true

        service.withdraw(WithdrawByPaymentCommand(accountId = "account-1", amount = 500, referenceId = "payment-1"))

        verify(exactly = 0) { accountRepository.findAccounts(any()) }
        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }

    @Test
    fun `silently ignores it when the target account does not exist`() {
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL) } returns false
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = "non-existent"))
        } returns (emptyList<Account>() to 0L)

        service.withdraw(WithdrawByPaymentCommand(accountId = "non-existent", amount = 500, referenceId = "payment-1"))

        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }
}
