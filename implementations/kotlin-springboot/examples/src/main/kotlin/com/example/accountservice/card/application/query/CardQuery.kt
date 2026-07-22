package com.example.accountservice.card.application.query

import com.example.accountservice.card.domain.Card
import com.example.accountservice.card.domain.CardFindQuery

/**
 * Read-only port — the narrow interface the Query Service depends on.
 *
 * Follows the `<Domain>Query` naming/placement (application/query/) prescribed by the root
 * `cqrs-pattern.md` (Card follows the same rule established for Account from the start — it does not
 * use a name like `CardQueryRepository`). Kept separate from the write model
 * ([com.example.accountservice.card.domain.CardRepository]), this enforces at compile time that the
 * Query Service cannot access write methods like saveCard. The actual implementation
 * (CardRepositoryImpl) implements both interfaces, but each Service is injected only with the
 * interface type it needs.
 *
 * `findCards` reuses the exact same signature as CardRepository (the write model) (the same pattern as
 * AccountQuery/PaymentQuery) — single lookups are handled via `CardFindQuery(take = 1, ...)` +
 * `.firstOrNull()` (see `GetCardService`).
 */
interface CardQuery {
    fun findCards(query: CardFindQuery): Pair<List<Card>, Long>
}
