package com.example.accountservice.card.domain;

/**
 * The write-side Repository contract for the Card Aggregate (owned by domain). Read-only lookups
 * are separated into a distinct application/query/CardQuery interface (see cqrs-pattern.md) —
 * however, Command use cases (looking up cards to suspend/cancel, verifying single-record
 * ownership) also need lookups, so {@code findCards} exists with the same signature in both
 * interfaces (the same pattern as payment/domain/PaymentRepository).
 */
public interface CardRepository {
    void saveCard(Card card);

    CardsWithCount findCards(CardFindQuery query);
}
