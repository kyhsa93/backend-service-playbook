package com.example.accountservice.card.application.query

import com.example.accountservice.card.domain.Card
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
    fun `카드가 존재하면 조회 결과를 반환한다`() {
        val card = Card.reconstitute(
            cardId = "card-1",
            accountId = "account-1",
            ownerId = "owner-1",
            brand = "VISA",
            status = CardStatus.ACTIVE,
            createdAt = LocalDateTime.now(),
        )
        every { cardQuery.findByCardIdAndOwnerId("card-1", "owner-1") } returns card

        val result = service.getCard("card-1", "owner-1")

        assertThat(result.cardId).isEqualTo("card-1")
        assertThat(result.status).isEqualTo("ACTIVE")
    }

    @Test
    fun `카드가 없으면 예외를 던진다`() {
        every { cardQuery.findByCardIdAndOwnerId("card-1", "owner-1") } returns null

        assertThrows<CardNotFoundException> { service.getCard("card-1", "owner-1") }
    }
}
