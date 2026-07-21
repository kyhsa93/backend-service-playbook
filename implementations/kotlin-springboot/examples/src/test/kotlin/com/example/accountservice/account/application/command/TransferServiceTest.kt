package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.InsufficientBalanceException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// TransferEligibilityService(Domain Service)는 순수 클래스라 목(mock)하지 않는다 — 이 스펙은
// Application 레이어가 두 계좌를 로드해 실제 판단 로직에 위임하고, 승인 시 두 계좌를 하나의
// 트랜잭션(saveAccounts)으로 저장하는 흐름을 검증한다.
class TransferServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = TransferService(accountRepository)

    @Test
    fun `승인되면 두 계좌를 하나의 트랜잭션으로 저장한다`() {
        val source = Account.create("owner-1", "KRW", "owner-1@example.com")
        source.deposit(10000)
        val target = Account.create("owner-2", "KRW", "owner-2@example.com")
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = source.accountId, ownerId = "owner-1"))
        } returns (listOf(source) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = target.accountId))
        } returns (listOf(target) to 1L)

        val result = service.transfer(TransferCommand(source.accountId, target.accountId, "owner-1", 4000))

        assertThat(result.sourceTransaction.type).isEqualTo("WITHDRAWAL")
        assertThat(result.targetTransaction.type).isEqualTo("DEPOSIT")
        assertThat(source.balance.amount).isEqualTo(6000)
        assertThat(target.balance.amount).isEqualTo(4000)
        verify(exactly = 1) { accountRepository.saveAccounts(source, target) }
    }

    @Test
    fun `출금 계좌를 찾을 수 없으면 예외를 던지고 저장하지 않는다`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = "missing", ownerId = "owner-1"))
        } returns (emptyList<Account>() to 0L)

        assertThrows<AccountNotFoundException> {
            service.transfer(TransferCommand("missing", "account-2", "owner-1", 1000))
        }
        verify(exactly = 0) { accountRepository.saveAccounts(any(), any()) }
    }

    @Test
    fun `잔액이 부족하면 예외를 던지고 저장하지 않는다`() {
        val source = Account.create("owner-1", "KRW", "owner-1@example.com")
        source.deposit(1000)
        val target = Account.create("owner-2", "KRW", "owner-2@example.com")
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = source.accountId, ownerId = "owner-1"))
        } returns (listOf(source) to 1L)
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = target.accountId))
        } returns (listOf(target) to 1L)

        assertThrows<InsufficientBalanceException> {
            service.transfer(TransferCommand(source.accountId, target.accountId, "owner-1", 5000))
        }
        verify(exactly = 0) { accountRepository.saveAccounts(any(), any()) }
    }
}
