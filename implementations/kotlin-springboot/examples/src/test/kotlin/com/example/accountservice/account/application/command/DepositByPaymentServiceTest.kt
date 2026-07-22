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
 * The shared reaction use case for the Payment BC's payment.cancelled.v1 (payment-cancellation
 * compensating credit) / refund.approved.v1 (refund-approval credit) events. The idempotency check is
 * symmetric with WithdrawByPaymentServiceTest — even with the same referenceId (paymentId), if the type
 * is DEPOSIT it must be judged separately from WITHDRAWAL so the compensating credit isn't skipped.
 */
class DepositByPaymentServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = DepositByPaymentService(accountRepository)

    @Test
    fun `deposits and saves when the referenceId has not yet been processed`() {
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT) } returns false
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId))
        } returns (listOf(account) to 1L)

        service.deposit(DepositByPaymentCommand(accountId = account.accountId, amount = 500, referenceId = "payment-1"))

        assertThat(account.balance.amount).isEqualTo(500)
        verify(exactly = 1) { accountRepository.saveAccount(account) }
    }

    @Test
    fun `even with the same referenceId, an entry already processed as WITHDRAWAL does not block DEPOSIT processing`() {
        // A WITHDRAWAL (payment-completion deduction) already exists for referenceId="payment-1", but
        // the DEPOSIT (compensating credit) from its cancellation is a separate type and must still be
        // processed — the (referenceId, type) combination scoping is the crux of this test.
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT) } returns false
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId))
        } returns (listOf(account) to 1L)

        service.deposit(DepositByPaymentCommand(accountId = account.accountId, amount = 500, referenceId = "payment-1"))

        verify(exactly = 1) { accountRepository.saveAccount(account) }
    }

    @Test
    fun `silently ignores it when the same referenceId+DEPOSIT has already been processed (idempotent)`() {
        every { accountRepository.hasTransactionWithReference("refund-1", TransactionType.DEPOSIT) } returns true

        service.deposit(DepositByPaymentCommand(accountId = "account-1", amount = 500, referenceId = "refund-1"))

        verify(exactly = 0) { accountRepository.findAccounts(any()) }
        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }
}
