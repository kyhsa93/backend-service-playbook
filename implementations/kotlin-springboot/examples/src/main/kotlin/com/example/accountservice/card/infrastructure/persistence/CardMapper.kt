package com.example.accountservice.card.infrastructure.persistence

import com.example.accountservice.card.domain.Card

/**
 * Object dedicated to converting between Card (pure domain) and CardJpaEntity (JPA mapping).
 * Used only inside CardRepositoryImpl — the Domain/Application layers know nothing of this object.
 */
internal object CardMapper {
    fun toDomain(entity: CardJpaEntity): Card =
        Card.reconstitute(
            cardId = entity.cardId,
            accountId = entity.accountId,
            ownerId = entity.ownerId,
            brand = entity.brand,
            status = entity.status,
            createdAt = entity.createdAt,
            lastStatementSentMonth = entity.lastStatementSentMonth,
        )

    /** Creates a new entity for a new Card (no PK, subject to insert). */
    fun toNewEntity(card: Card): CardJpaEntity =
        CardJpaEntity(
            id = null,
            cardId = card.cardId,
            accountId = card.accountId,
            ownerId = card.ownerId,
            brand = card.brand,
            status = card.status,
            createdAt = card.createdAt,
            lastStatementSentMonth = card.lastStatementSentMonth,
        )

    /** Applies the domain Card's latest state (status/lastStatementSentMonth) onto an existing entity (PK preserved) — subject to update. */
    fun updateEntity(
        entity: CardJpaEntity,
        card: Card,
    ): CardJpaEntity {
        entity.status = card.status
        entity.lastStatementSentMonth = card.lastStatementSentMonth
        return entity
    }
}
