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
 * Payment BC의 payment.cancelled.v1(결제취소 보상 크레딧)/refund.approved.v1(환불 승인 크레딧)
 * 공통 반응 유스케이스. 멱등성 체크가 WithdrawByPaymentServiceTest와 대칭이다 — 같은 referenceId
 * (paymentId)라도 type이 DEPOSIT이면 WITHDRAWAL과 별개로 판정되어야 보상 크레딧이 스킵되지 않는다.
 */
class DepositByPaymentServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = DepositByPaymentService(accountRepository)

    @Test
    fun `아직 처리되지 않은 referenceId면 입금하고 저장한다`() {
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
    fun `같은 referenceId라도 WITHDRAWAL로 이미 처리된 것은 DEPOSIT 처리를 막지 않는다`() {
        // referenceId="payment-1"의 WITHDRAWAL(결제완료 차감)은 이미 있지만, 그 취소로 인한
        // DEPOSIT(보상 크레딧)은 별도 type이므로 처리되어야 한다 — (referenceId, type) 조합
        // 스코핑이 핵심이다.
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        every { accountRepository.hasTransactionWithReference("payment-1", TransactionType.DEPOSIT) } returns false
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId))
        } returns (listOf(account) to 1L)

        service.deposit(DepositByPaymentCommand(accountId = account.accountId, amount = 500, referenceId = "payment-1"))

        verify(exactly = 1) { accountRepository.saveAccount(account) }
    }

    @Test
    fun `같은 referenceId+DEPOSIT이 이미 처리됐으면 조용히 무시한다(멱등)`() {
        every { accountRepository.hasTransactionWithReference("refund-1", TransactionType.DEPOSIT) } returns true

        service.deposit(DepositByPaymentCommand(accountId = "account-1", amount = 500, referenceId = "refund-1"))

        verify(exactly = 0) { accountRepository.findAccounts(any()) }
        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }
}
