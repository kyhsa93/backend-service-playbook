package com.example.accountservice.account.application.command

import com.example.accountservice.account.domain.AccountRepository
import com.example.accountservice.outbox.OutboxRelay
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CreateAccountServiceTest {
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val outboxRelay = mockk<OutboxRelay>(relaxed = true)
    private val service = CreateAccountService(accountRepository, outboxRelay)

    @Test
    fun `계좌 생성 시 저장되고 결과에 초기 잔액 0이 담긴다`() {
        val result = service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        assertThat(result.ownerId).isEqualTo("owner-1")
        assertThat(result.balance.amount).isEqualTo(0)
        verify(exactly = 1) { accountRepository.saveAccount(any()) }
    }

    @Test
    fun `저장 직후 outboxRelay가 드레인된다`() {
        service.create(CreateAccountCommand("owner-1", "KRW", "owner-1@example.com"))

        verifyOrder {
            accountRepository.saveAccount(any())
            outboxRelay.processPending()
        }
    }
}
