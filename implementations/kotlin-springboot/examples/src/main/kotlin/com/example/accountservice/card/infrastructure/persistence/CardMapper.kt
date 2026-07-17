package com.example.accountservice.card.infrastructure.persistence

import com.example.accountservice.card.domain.Card

/**
 * Card(순수 도메인) ↔ CardJpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * CardRepositoryImpl 내부에서만 사용된다 — Domain/Application 레이어는 이 오브젝트를 알지 못한다.
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
        )

    /** 신규 Card를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
    fun toNewEntity(card: Card): CardJpaEntity =
        CardJpaEntity(
            id = null,
            cardId = card.cardId,
            accountId = card.accountId,
            ownerId = card.ownerId,
            brand = card.brand,
            status = card.status,
            createdAt = card.createdAt,
        )

    /** 기존 엔티티(PK 보존)에 도메인 Card의 최신 상태(status)를 반영한다 — update 대상. */
    fun updateEntity(
        entity: CardJpaEntity,
        card: Card,
    ): CardJpaEntity {
        entity.status = card.status
        return entity
    }
}
