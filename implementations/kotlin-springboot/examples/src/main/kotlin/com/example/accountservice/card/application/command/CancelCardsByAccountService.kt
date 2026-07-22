package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import org.springframework.stereotype.Service

/**
 * The reaction use case for the Account BC's `account.closed.v1` Integration Event.
 *
 * Only cards that are not yet cancelled (ACTIVE, SUSPENDED) are cancelled, so it is idempotent under
 * redelivery. This Service itself has no transaction boundary — it is called from within a
 * higher-level transaction that wraps the outbox drain loop (see domain-events.md and
 * persistence.md's transaction boundary rules).
 */
@Service
class CancelCardsByAccountService(
    private val cardRepository: CardRepository,
) {
    fun cancel(accountId: String) {
        val (cards, _) =
            cardRepository.findCards(
                CardFindQuery(
                    page = 0,
                    take = 1000,
                    accountId = accountId,
                    status = listOf(CardStatus.ACTIVE, CardStatus.SUSPENDED),
                ),
            )
        for (card in cards) {
            card.cancel()
            cardRepository.saveCard(card)
        }
    }
}
