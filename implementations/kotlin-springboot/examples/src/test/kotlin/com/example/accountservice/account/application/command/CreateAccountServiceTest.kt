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
    fun `계좌 생성 시 저장되고 결과에 초기 잔액 0이 담긴다`() {
        val result = service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        assertThat(result.ownerId).isEqualTo("owner-1")
        assertThat(result.balance.amount).isEqualTo(0)
        verify(exactly = 1) { accountRepository.saveAccount(any()) }
    }
}
