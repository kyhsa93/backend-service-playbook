package com.example.accountservice.account.domain

/**
 * The common layer for Domain Events published by the Account Aggregate.
 *
 * This `sealed interface` groups all 6 event types together, so the compiler checks exhaustiveness in
 * any `when` branch that handles events (including future code yet to be added) — if a new event type is
 * added and its handling is missed, it is caught at compile time. The collection type returned by
 * `Account.pullDomainEvents()` also uses `List<DomainEvent>` instead of `List<Any>` (see
 * domain-events.md step 1).
 *
 * `accountId`/`email` are fields common to every event, so they are declared as a contract here — the
 * timestamp field names that differ per event (`createdAt`/`suspendedAt`/`reactivatedAt`/`closedAt`) are
 * not unified.
 */
sealed interface DomainEvent {
    val accountId: String
    val email: String
}
