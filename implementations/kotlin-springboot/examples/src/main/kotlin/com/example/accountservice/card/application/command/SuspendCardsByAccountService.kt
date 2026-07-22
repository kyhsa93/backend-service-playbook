package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import org.springframework.stereotype.Service

/**
 * The reaction use case for the Account BC's `account.suspended.v1` Integration Event.
 *
 * Implemented idempotently on the assumption of at-least-once delivery — since only ACTIVE cards are
 * selected for suspension, redelivery of the same event (against an already-suspended card) does
 * nothing. This Service itself has no transaction boundary — it is called from within a higher-level
 * transaction that wraps the outbox drain loop (see domain-events.md and persistence.md's transaction
 * boundary rules).
 */
@Service
class SuspendCardsByAccountService(
    private val cardRepository: CardRepository,
) {
    fun suspend(accountId: String) {
        val (cards, _) =
            cardRepository.findCards(
                CardFindQuery(page = 0, take = 1000, accountId = accountId, status = listOf(CardStatus.ACTIVE)),
            )
        for (card in cards) {
            card.suspend()
            cardRepository.saveCard(card)
        }
    }
}
