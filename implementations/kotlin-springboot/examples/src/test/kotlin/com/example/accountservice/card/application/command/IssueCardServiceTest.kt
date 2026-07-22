package com.example.accountservice.card.application.command

import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.application.adapter.AccountView
import com.example.accountservice.card.domain.CardIssueRequiresActiveAccountException
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import com.example.accountservice.card.domain.LinkedAccountNotFoundException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IssueCardServiceTest {
    private val cardRepository = mockk<CardRepository>(relaxed = true)
    private val accountAdapter = mockk<AccountAdapter>()
    private val service = IssueCardService(cardRepository, accountAdapter)

    @Test
    fun `issues and saves a card when the account is active`() {
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = true, email = "owner-1@example.com")

        val result = service.issue(IssueCardCommand(accountId = "account-1", brand = "VISA", requesterId = "owner-1"))

        assertThat(result.accountId).isEqualTo("account-1")
        assertThat(result.ownerId).isEqualTo("owner-1")
        assertThat(result.status).isEqualTo(CardStatus.ACTIVE.name)
        verify(exactly = 1) { cardRepository.saveCard(any()) }
    }

    @Test
    fun `throws an exception when the linked account cannot be found`() {
        every { accountAdapter.findAccount("account-1", "owner-1") } returns null

        assertThrows<LinkedAccountNotFoundException> {
            service.issue(IssueCardCommand(accountId = "account-1", brand = "VISA", requesterId = "owner-1"))
        }
        verify(exactly = 0) { cardRepository.saveCard(any()) }
    }

    @Test
    fun `throws an exception when the linked account is inactive`() {
        every { accountAdapter.findAccount("account-1", "owner-1") } returns
            AccountView(accountId = "account-1", active = false, email = "owner-1@example.com")

        assertThrows<CardIssueRequiresActiveAccountException> {
            service.issue(IssueCardCommand(accountId = "account-1", brand = "VISA", requesterId = "owner-1"))
        }
        verify(exactly = 0) { cardRepository.saveCard(any()) }
    }
}
