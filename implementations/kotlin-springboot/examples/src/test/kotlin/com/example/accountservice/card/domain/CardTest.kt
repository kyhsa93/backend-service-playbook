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
    fun `issue produces a card in the ACTIVE state`() {
        val card = Card.issue(accountId = "account-1", ownerId = "owner-1", brand = "VISA")

        assertThat(card.status).isEqualTo(CardStatus.ACTIVE)
        assertThat(card.accountId).isEqualTo("account-1")
        assertThat(card.ownerId).isEqualTo("owner-1")
        assertThat(card.brand).isEqualTo("VISA")
        assertThat(card.cardId).isNotBlank()
    }

    @Test
    fun `the card ID is a 32-character hex string without hyphens`() {
        val card = Card.issue(accountId = "account-1", ownerId = "owner-1", brand = "VISA")

        assertThat(card.cardId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `suspending an active card transitions to SUSPENDED`() {
        val card = createCard(CardStatus.ACTIVE)

        card.suspend()

        assertThat(card.status).isEqualTo(CardStatus.SUSPENDED)
    }

    @Test
    fun `suspending an already-suspended card throws an exception`() {
        val card = createCard(CardStatus.SUSPENDED)

        assertThrows<CardAlreadySuspendedException> { card.suspend() }
    }

    @Test
    fun `suspending a cancelled card throws an exception`() {
        val card = createCard(CardStatus.CANCELLED)

        assertThrows<CancelledCardCannotBeSuspendedException> { card.suspend() }
    }

    @Test
    fun `cancelling an active card transitions to CANCELLED`() {
        val card = createCard(CardStatus.ACTIVE)

        card.cancel()

        assertThat(card.status).isEqualTo(CardStatus.CANCELLED)
    }

    @Test
    fun `cancelling a suspended card transitions to CANCELLED`() {
        val card = createCard(CardStatus.SUSPENDED)

        card.cancel()

        assertThat(card.status).isEqualTo(CardStatus.CANCELLED)
    }

    @Test
    fun `cancelling an already-cancelled card throws an exception`() {
        val card = createCard(CardStatus.CANCELLED)

        assertThrows<CardAlreadyCancelledException> { card.cancel() }
    }
}
