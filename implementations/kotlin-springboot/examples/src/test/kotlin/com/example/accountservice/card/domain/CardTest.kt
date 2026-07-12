package com.example.accountservice.card.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class CardTest {

    private fun createCard(status: CardStatus = CardStatus.ACTIVE): Card =
        Card.reconstitute(
            cardId = "card-1",
            accountId = "account-1",
            ownerId = "owner-1",
            brand = "VISA",
            status = status,
            createdAt = LocalDateTime.now(),
        )

    @Test
    fun `issue 하면 ACTIVE 상태의 카드가 생성된다`() {
        val card = Card.issue(accountId = "account-1", ownerId = "owner-1", brand = "VISA")

        assertThat(card.status).isEqualTo(CardStatus.ACTIVE)
        assertThat(card.accountId).isEqualTo("account-1")
        assertThat(card.ownerId).isEqualTo("owner-1")
        assertThat(card.brand).isEqualTo("VISA")
        assertThat(card.cardId).isNotBlank()
    }

    @Test
    fun `카드 ID는 하이픈 없는 32자리 hex 문자열이다`() {
        val card = Card.issue(accountId = "account-1", ownerId = "owner-1", brand = "VISA")

        assertThat(card.cardId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `활성 카드를 정지하면 SUSPENDED 상태가 된다`() {
        val card = createCard(CardStatus.ACTIVE)

        card.suspend()

        assertThat(card.status).isEqualTo(CardStatus.SUSPENDED)
    }

    @Test
    fun `이미 정지된 카드를 정지하면 예외를 던진다`() {
        val card = createCard(CardStatus.SUSPENDED)

        assertThrows<CardAlreadySuspendedException> { card.suspend() }
    }

    @Test
    fun `해지된 카드를 정지하면 예외를 던진다`() {
        val card = createCard(CardStatus.CANCELLED)

        assertThrows<CancelledCardCannotBeSuspendedException> { card.suspend() }
    }

    @Test
    fun `활성 카드를 해지하면 CANCELLED 상태가 된다`() {
        val card = createCard(CardStatus.ACTIVE)

        card.cancel()

        assertThat(card.status).isEqualTo(CardStatus.CANCELLED)
    }

    @Test
    fun `정지된 카드를 해지하면 CANCELLED 상태가 된다`() {
        val card = createCard(CardStatus.SUSPENDED)

        card.cancel()

        assertThat(card.status).isEqualTo(CardStatus.CANCELLED)
    }

    @Test
    fun `이미 해지된 카드를 해지하면 예외를 던진다`() {
        val card = createCard(CardStatus.CANCELLED)

        assertThrows<CardAlreadyCancelledException> { card.cancel() }
    }
}
