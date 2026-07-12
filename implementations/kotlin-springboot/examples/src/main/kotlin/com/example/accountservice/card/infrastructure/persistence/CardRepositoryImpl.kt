package com.example.accountservice.card.infrastructure.persistence

import com.example.accountservice.card.application.query.CardQuery
import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Card 쓰기 모델([CardRepository])과 읽기 모델([CardQuery])을 함께 구현하는 구현체.
 * 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받으므로, Query Service는 save에 접근할 수 없다.
 */
@Repository
class CardRepositoryImpl(
    private val jpaRepository: CardJpaRepository,
) : CardRepository, CardQuery {

    @Transactional
    override fun save(card: Card) {
        val entity = jpaRepository.findByCardId(card.cardId)
            ?.let { CardMapper.updateEntity(it, card) }
            ?: CardMapper.toNewEntity(card)
        jpaRepository.save(entity)
    }

    override fun findByAccountIdAndStatuses(accountId: String, statuses: List<CardStatus>): List<Card> =
        jpaRepository.findByAccountIdAndStatusInOrderByCardIdDesc(accountId, statuses)
            .map(CardMapper::toDomain)

    override fun findByCardIdAndOwnerId(cardId: String, ownerId: String): Card? =
        jpaRepository.findByCardIdAndOwnerId(cardId, ownerId)?.let(CardMapper::toDomain)
}
