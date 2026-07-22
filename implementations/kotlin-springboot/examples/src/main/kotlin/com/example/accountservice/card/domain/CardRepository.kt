package com.example.accountservice.card.domain

/**
 * Card write-model port — the interface the Command Service depends on.
 *
 * Read-only queries are separated into [com.example.accountservice.card.application.query.CardQuery]
 * (cqrs-pattern.md). The actual implementation (CardRepositoryImpl) implements both interfaces,
 * but each Service is injected only with the interface type it needs.
 *
 * Following the root `repository-pattern.md`'s `find<Noun>s` unification rule, list/single/conditional
 * lookups are all handled through the single `findCards` method (the same shape as account/payment's
 * `AccountRepository`/`PaymentRepository`). `CancelCardsByAccountService`/`SuspendCardsByAccountService`
 * need all of an account's cards in a given status, so `take` is given a large enough value (this is
 * an internal reaction use case, not subject to REST pagination).
 */
interface CardRepository {
    fun findCards(query: CardFindQuery): Pair<List<Card>, Long>

    fun saveCard(card: Card)
}

data class CardFindQuery(
    val page: Int,
    val take: Int,
    val cardId: String? = null,
    val ownerId: String? = null,
    val accountId: String? = null,
    val status: List<CardStatus>? = null,
    // Filter used only by SendMonthlyCardStatementsService — filters out at query time any card
    // that has already been sent this month's ("yyyy-MM") statement (lastStatementSentMonth = this value).
    val excludeStatementMonth: String? = null,
)
