package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CancelCardsByAccountServiceTest {
    private val cardRepository = mockk<CardRepository>(relaxed = true)
    private val service = CancelCardsByAccountService(cardRepository)

    private fun card(
        cardId: String,
        status: CardStatus,
    ): Card =
        Card.reconstitute(
            cardId = cardId,
            accountId = "account-1",
            ownerId = "owner-1",
            brand = "VISA",
            status = status,
            createdAt = LocalDateTime.now(),
        )

    private val findQuery =
        CardFindQuery(
            page = 0,
            take = 1000,
            accountId = "account-1",
            status = listOf(CardStatus.ACTIVE, CardStatus.SUSPENDED),
        )

    @Test
    fun `계좌의 ACTIVE SUSPENDED 카드를 모두 해지하고 저장한다`() {
        val cards = listOf(card("card-1", CardStatus.ACTIVE), card("card-2", CardStatus.SUSPENDED))
        every { cardRepository.findCards(findQuery) } returns (cards to cards.size.toLong())

        service.cancel("account-1")

        cards.forEach { assertThat(it.status).isEqualTo(CardStatus.CANCELLED) }
        verify(exactly = 2) { cardRepository.saveCard(any()) }
    }

    @Test
    fun `해지 대상 카드가 없으면 아무 것도 저장하지 않는다 (멱등)`() {
        every { cardRepository.findCards(findQuery) } returns (emptyList<Card>() to 0L)

        service.cancel("account-1")

        verify(exactly = 0) { cardRepository.saveCard(any()) }
    }
}
