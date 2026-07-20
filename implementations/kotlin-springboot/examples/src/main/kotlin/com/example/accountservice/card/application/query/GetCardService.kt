package com.example.accountservice.card.application.query

import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetCardService(
    private val cardQuery: CardQuery,
) {
    fun getCard(
        cardId: String,
        requesterId: String,
    ): GetCardResult {
        val (cards, _) =
            cardQuery.findCards(CardFindQuery(page = 0, take = 1, cardId = cardId, ownerId = requesterId))
        val card = cards.firstOrNull() ?: throw CardNotFoundException(cardId)
        return GetCardResult(
            cardId = card.cardId,
            accountId = card.accountId,
            ownerId = card.ownerId,
            brand = card.brand,
            status = card.status.name,
            createdAt = card.createdAt,
        )
    }
}
