package com.example.accountservice.card.infrastructure.persistence;

import com.example.accountservice.card.domain.Card;

/**
 * Card(순수 도메인) ↔ CardJpaEntity(JPA 매핑) 변환 전담 클래스. CardRepositoryImpl 내부에서만 사용된다 —
 * Domain/Application 레이어는 이 클래스를 알지 못한다.
 */
final class CardMapper {

    private CardMapper() {}

    static Card toDomain(CardJpaEntity entity) {
        return Card.reconstitute(
                entity.getCardId(),
                entity.getAccountId(),
                entity.getOwnerId(),
                entity.getBrand(),
                entity.getStatus(),
                entity.getCreatedAt());
    }

    /** 신규 Card를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
    static CardJpaEntity toNewEntity(Card card) {
        return new CardJpaEntity(
                null,
                card.getCardId(),
                card.getAccountId(),
                card.getOwnerId(),
                card.getBrand(),
                card.getStatus(),
                card.getCreatedAt());
    }

    /** 기존 엔티티(PK 보존)에 도메인 Card의 최신 상태를 반영한다 — update 대상. */
    static CardJpaEntity updateEntity(CardJpaEntity entity, Card card) {
        entity.applyMutableState(card.getStatus());
        return entity;
    }
}
