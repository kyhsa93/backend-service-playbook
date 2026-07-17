package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class SuspendCardsByAccountServiceTest {
    private val cardRepository = mockk<CardRepository>(relaxed = true)
    private val service = SuspendCardsByAccountService(cardRepository)

    private fun activeCard(cardId: String): Card =
        Card.reconstitute(
            cardId = cardId,
            accountId = "account-1",
            ownerId = "owner-1",
            brand = "VISA",
            status = CardStatus.ACTIVE,
            createdAt = LocalDateTime.now(),
        )

    @Test
    fun `계좌의 ACTIVE 카드를 모두 정지하고 저장한다`() {
        val cards = listOf(activeCard("card-1"), activeCard("card-2"))
        every { cardRepository.findByAccountIdAndStatuses("account-1", listOf(CardStatus.ACTIVE)) } returns cards

        service.suspend("account-1")

        cards.forEach { assertThat(it.status).isEqualTo(CardStatus.SUSPENDED) }
        verify(exactly = 2) { cardRepository.save(any()) }
    }

    @Test
    fun `ACTIVE 카드가 없으면 아무 것도 저장하지 않는다 (멱등)`() {
        every { cardRepository.findByAccountIdAndStatuses("account-1", listOf(CardStatus.ACTIVE)) } returns emptyList()

        service.suspend("account-1")

        verify(exactly = 0) { cardRepository.save(any()) }
    }
}
