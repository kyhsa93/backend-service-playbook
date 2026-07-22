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

// TransferEligibilityService (a Domain Service) is a plain class, so it is not mocked — this spec
// verifies the flow where the Application layer loads both accounts, delegates to the real decision
// logic, and, when approved, saves both accounts in a single transaction (saveAccounts).
class TransferServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = TransferService(accountRepository)

    @Test
    fun `saves both accounts in a single transaction when approved`() {
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
    fun `throws an exception and does not save when the withdrawal account cannot be found`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = "missing", ownerId = "owner-1"))
        } returns (emptyList<Account>() to 0L)

        assertThrows<AccountNotFoundException> {
            service.transfer(TransferCommand("missing", "account-2", "owner-1", 1000))
        }
        verify(exactly = 0) { accountRepository.saveAccounts(any(), any()) }
    }

    @Test
    fun `throws an exception and does not save when the balance is insufficient`() {
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
