package com.example.accountservice.card.application.query

import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardNotFoundException
import com.example.accountservice.card.domain.CardStatus
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class GetCardServiceTest {
    private val cardQuery = mockk<CardQuery>()
    private val service = GetCardService(cardQuery)

    @Test
    fun `returns the lookup result when the card exists`() {
        val card =
            Card.reconstitute(
                cardId = "card-1",
                accountId = "account-1",
                ownerId = "owner-1",
                brand = "VISA",
                status = CardStatus.ACTIVE,
                createdAt = LocalDateTime.now(),
            )
        val findQuery = CardFindQuery(page = 0, take = 1, cardId = "card-1", ownerId = "owner-1")
        every { cardQuery.findCards(findQuery) } returns (listOf(card) to 1L)

        val result = service.getCard("card-1", "owner-1")

        assertThat(result.cardId).isEqualTo("card-1")
        assertThat(result.status).isEqualTo("ACTIVE")
    }

    @Test
    fun `throws an exception when the card does not exist`() {
        val findQuery = CardFindQuery(page = 0, take = 1, cardId = "card-1", ownerId = "owner-1")
        every { cardQuery.findCards(findQuery) } returns (emptyList<Card>() to 0L)

        assertThrows<CardNotFoundException> { service.getCard("card-1", "owner-1") }
    }
}
