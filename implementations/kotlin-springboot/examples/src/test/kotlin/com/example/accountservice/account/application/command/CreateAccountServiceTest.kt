package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountRepository
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CreateAccountServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val service = CreateAccountService(accountRepository)

    @Test
    fun `creating an account saves it and the result carries an initial balance of 0`() {
        val result = service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        assertThat(result.ownerId).isEqualTo("owner-1")
        assertThat(result.balance.amount).isEqualTo(0)
        verify(exactly = 1) { accountRepository.saveAccount(any()) }
    }
}
