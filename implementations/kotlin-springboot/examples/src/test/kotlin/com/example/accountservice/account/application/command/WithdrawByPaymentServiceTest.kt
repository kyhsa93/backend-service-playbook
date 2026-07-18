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
 * Payment BC의 payment.completed.v1 반응 유스케이스 — Level 2(Ledger) 멱등성이 (referenceId, type)
 * 조합으로 스코핑되는지가 이 테스트의 핵심이다. referenceId만으로 확인했다면 결제완료(WITHDRAWAL)와
 * 그 보상 크레딧(DEPOSIT, DepositByPaymentServiceTest 참고)이 같은 paymentId를 공유할 때 보상
 * 크레딧이 "이미 처리됨"으로 잘못 스킵된다 — 이 버그를 nestjs 레퍼런스 구현에서 실제로 겪고 고쳤다.
 */
class WithdrawByPaymentServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = WithdrawByPaymentService(accountRepository)

    @Test
    fun `아직 처리되지 않은 referenceId면 출금하고 저장한다`() {
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
    fun `같은 referenceId+WITHDRAWAL이 이미 처리됐으면 조용히 무시한다(멱등)`() {
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL) } returns true

        service.withdraw(WithdrawByPaymentCommand(accountId = "account-1", amount = 500, referenceId = "payment-1"))

        verify(exactly = 0) { accountRepository.findAccounts(any()) }
        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }

    @Test
    fun `대상 계좌가 없으면 조용히 무시한다`() {
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.WITHDRAWAL) } returns false
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = "non-existent"))
        } returns (emptyList<Account>() to 0L)

        service.withdraw(WithdrawByPaymentCommand(accountId = "non-existent", amount = 500, referenceId = "payment-1"))

        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }
}
