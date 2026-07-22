package com.example.accountservice.card.infrastructure.persistence;

import com.example.accountservice.card.domain.Card;
import java.time.YearMonth;

/**
 * A class dedicated to converting between Card (pure domain) and CardJpaEntity (JPA mapping). Used
 * only internally within CardRepositoryImpl — the Domain/Application layers have no knowledge of
 * this class.
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
                entity.getCreatedAt(),
                toYearMonth(entity.getLastStatementSentMonth()));
    }

    /** Creates a new entity (no PK, to be inserted) for a newly issued Card. */
    static CardJpaEntity toNewEntity(Card card) {
        return new CardJpaEntity(
                null,
                card.getCardId(),
                card.getAccountId(),
                card.getOwnerId(),
                card.getBrand(),
                card.getStatus(),
                card.getCreatedAt(),
                toColumn(card.getLastStatementSentMonth()));
    }

    /**
     * Applies the domain Card's latest state onto an existing entity (preserving its PK) — to be
     * updated.
     */
    static CardJpaEntity updateEntity(CardJpaEntity entity, Card card) {
        entity.applyMutableState(card.getStatus(), toColumn(card.getLastStatementSentMonth()));
        return entity;
    }

    private static YearMonth toYearMonth(String column) {
        return column == null ? null : YearMonth.parse(column);
    }

    private static String toColumn(YearMonth yearMonth) {
        return yearMonth == null ? null : yearMonth.toString();
    }
}
