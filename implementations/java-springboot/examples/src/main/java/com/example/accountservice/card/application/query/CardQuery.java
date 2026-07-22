package com.example.accountservice.card.application.query;

import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardsWithCount;

/**
 * A read-only interface dedicated to the Query Service. A narrow contract separate from the
 * write-side {@code CardRepository} (domain). The Query Service should depend only on this
 * interface — it does not expose write methods such as {@code saveCard} (see cqrs-pattern.md).
 */
public interface CardQuery {
    CardsWithCount findCards(CardFindQuery query);
}
