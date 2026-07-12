package com.example.accountservice.card.application.query

import com.example.accountservice.card.domain.CardNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetCardService(private val cardQuery: CardQuery) {

    fun getCard(cardId: String, requesterId: String): GetCardResult {
        val card = cardQuery.findByCardIdAndOwnerId(cardId, requesterId)
            ?: throw CardNotFoundException(cardId)
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
