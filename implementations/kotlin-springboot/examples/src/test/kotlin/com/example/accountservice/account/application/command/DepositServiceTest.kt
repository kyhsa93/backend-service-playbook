package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.account.domain.DepositRequiresActiveAccountException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DepositServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = DepositService(accountRepository)

    @Test
    fun `when the account exists, the balance increases and a save occurs`() {
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId, ownerId = "owner-1"))
        } returns (listOf(account) to 1L)

        val result = service.deposit(DepositCommand(account.accountId, "owner-1", 500))

        assertThat(result.type).isEqualTo("DEPOSIT")
        assertThat(account.balance.amount).isEqualTo(500)
        verify(exactly = 1) { accountRepository.saveAccount(account) }
    }

    @Test
    fun `throws an exception when the account does not exist`() {
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = "non-existent", ownerId = "owner-1"))
        } returns (emptyList<Account>() to 0L)

        assertThrows<AccountNotFoundException> {
            service.deposit(DepositCommand("non-existent", "owner-1", 500))
        }
    }

    @Test
    fun `throws an exception and does not save when the account is suspended`() {
        val account = Account.create("owner-1", "KRW", "owner-1@example.com")
        account.suspend()
        every {
            accountRepository.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = account.accountId, ownerId = "owner-1"))
        } returns (listOf(account) to 1L)

        assertThrows<DepositRequiresActiveAccountException> {
            service.deposit(DepositCommand(account.accountId, "owner-1", 500))
        }
        verify(exactly = 0) { accountRepository.saveAccount(any()) }
    }
}
