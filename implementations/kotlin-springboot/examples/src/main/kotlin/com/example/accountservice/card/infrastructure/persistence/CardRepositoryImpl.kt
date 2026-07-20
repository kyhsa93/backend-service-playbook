package com.example.accountservice.card.infrastructure.persistence

import com.example.accountservice.card.application.query.CardQuery
import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Card 쓰기 모델([CardRepository])과 읽기 모델([CardQuery])을 함께 구현하는 구현체.
 * 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받으므로, Query Service는 saveCard에 접근할 수 없다.
 *
 * `findCards`는 두 인터페이스가 정확히 같은 시그니처로 선언하므로 하나의 override가 동시에 만족시킨다
 * (account/infrastructure/persistence/AccountRepositoryImpl과 동일한 구조).
 */
@Repository
class CardRepositoryImpl(
    private val jpaRepository: CardJpaRepository,
    private val em: EntityManager,
) : CardRepository,
    CardQuery {
    override fun findCards(query: CardFindQuery): Pair<List<Card>, Long> {
        val jpql = buildJpql(query, count = false)
        val cards =
            em
                .createQuery(jpql, CardJpaEntity::class.java)
                .setFirstResult(query.page * query.take)
                .setMaxResults(query.take)
                .apply { applyParams(this, query) }
                .resultList
                .map(CardMapper::toDomain)
        val countJpql = buildJpql(query, count = true)
        val count =
            em
                .createQuery(countJpql, Long::class.java)
                .apply { applyParams(this, query) }
                .singleResult
        return cards to count
    }

    @Transactional
    override fun saveCard(card: Card) {
        val entity =
            jpaRepository
                .findByCardId(card.cardId)
                ?.let { CardMapper.updateEntity(it, card) }
                ?: CardMapper.toNewEntity(card)
        jpaRepository.save(entity)
    }

    private fun buildJpql(
        query: CardFindQuery,
        count: Boolean,
    ): String {
        val select = if (count) "SELECT COUNT(c)" else "SELECT c"
        val sb = StringBuilder("$select FROM CardJpaEntity c WHERE 1 = 1")
        if (!query.cardId.isNullOrBlank()) sb.append(" AND c.cardId = :cardId")
        if (!query.ownerId.isNullOrBlank()) sb.append(" AND c.ownerId = :ownerId")
        if (!query.accountId.isNullOrBlank()) sb.append(" AND c.accountId = :accountId")
        if (!query.status.isNullOrEmpty()) sb.append(" AND c.status IN :status")
        if (!count) sb.append(" ORDER BY c.cardId DESC")
        return sb.toString()
    }

    private fun applyParams(
        q: Query,
        query: CardFindQuery,
    ) {
        if (!query.cardId.isNullOrBlank()) q.setParameter("cardId", query.cardId)
        if (!query.ownerId.isNullOrBlank()) q.setParameter("ownerId", query.ownerId)
        if (!query.accountId.isNullOrBlank()) q.setParameter("accountId", query.accountId)
        if (!query.status.isNullOrEmpty()) q.setParameter("status", query.status)
    }
}
