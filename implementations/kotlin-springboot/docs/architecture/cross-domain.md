# Cross-Domain Call Patterns — Kotlin Spring Boot

> For the principles and selection criteria (synchronous Adapter vs. asynchronous Integration Event), see [root cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md). This document covers **the concrete Kotlin/Spring implementation of both the synchronous Adapter pattern and the asynchronous Integration Event pattern**.

## This repository's current state — actual code for the Account ↔ Card BCs

`examples/` implements two Bounded Contexts, Account and Card (`notification/` is not a separate BC — it's a Technical Service, see [directory-structure.md](directory-structure.md)). The two BCs use communication patterns in opposite directions from each other.

- **Synchronous Adapter (ACL)**: because the Card BC must query the Account BC immediately when a card is issued (whether the linked account exists and is active is needed for the response), `card/application/adapter/AccountAdapter.kt` + `card/infrastructure/AccountAdapterImpl.kt` call the Account BC's read port (`account/application/query/AccountQuery`) synchronously.
- **Asynchronous Integration Event**: even when an Account is suspended/closed, the Card BC doesn't need to reflect that result (linked cards transitioning to SUSPENDED/CANCELLED) in a response immediately, so the Account BC publishes an `account.suspended.v1`/`account.closed.v1` Integration Event via the Outbox, and the Card BC reacts asynchronously.

Below, both patterns are explained against the actual code.

## Principle — where this differs from NestJS

Like the Repository, NestJS defines the Adapter interface as an `abstract class` (because the NestJS DI container can't use a plain `interface` as a runtime token). **In Kotlin/Spring, for the same reason as the Repository pattern, a plain `interface` itself becomes the DI token** — Spring finds the sole implementation of that interface on the classpath and auto-binds it, so no `abstract class` workaround is needed (see the "Domain layer" section of [layer-architecture.md](layer-architecture.md)).

1. **An Application Service calls an external BC only through an Adapter interface** — it never directly injects another BC's Service/Repository. The harness's `no-cross-bc-repository-in-application` rule scans a file under `application/` for imports and fails if it directly imports a `domain/*Repository`·`*Query` from a domain other than its own — an import within the same domain (e.g. `account/application/` using `AccountRepository`) is a normal pattern and not the target of this rule.
2. **The Adapter interface is defined as a Kotlin `interface` in the caller's (the Card BC's) `application/adapter/`.**
3. **The Adapter implementation is placed as a `@Component` in the caller's `infrastructure/`**, and constructor-injects whatever Service/Query the external BC's module publicly exposes (its public beans, equivalent to `exports`).
4. An Adapter is **read-only** — it never calls an external BC's state-changing methods. If a write is needed, switch to an Integration Event (see the [root document](../../../../docs/architecture/cross-domain-communication.md)).

## Example 1 — Card BC synchronously querying the Account BC (actual code)

```kotlin
// card/application/adapter/AccountAdapter.kt — the interface
package com.example.accountservice.card.application.adapter

interface AccountAdapter {
    fun findAccount(accountId: String, ownerId: String): AccountView?
}

data class AccountView(
    val accountId: String,
    val active: Boolean,
)
```

It's an `interface`, not an `abstract class` — in Kotlin, no framework dependency is needed here at all. The return type being `AccountView?` (nullable) is also carrying the root's "not found" representation over into Kotlin's null-safety — the caller can't compile without handling it via `?:` (the same idiom applied to the Repository in [layer-architecture.md](layer-architecture.md)). The key point is that `AccountView` exposes only `active: Boolean` and never passes through the Account BC's `AccountStatus` enum (`ACTIVE`/`SUSPENDED`/`CLOSED`) itself — even if Account adds/changes states, Card can keep answering only the question it actually needs: "is it active."

```kotlin
// card/infrastructure/AccountAdapterImpl.kt — the implementation (ACL)
package com.example.accountservice.card.infrastructure

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.application.adapter.AccountView
import org.springframework.stereotype.Component

@Component
class AccountAdapterImpl(
    private val accountQuery: AccountQuery,
) : AccountAdapter {

    override fun findAccount(accountId: String, ownerId: String): AccountView? {
        val (accounts, _) = accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = ownerId))
        return accounts.firstOrNull()?.let { account ->
            AccountView(accountId = account.accountId, active = account.status == AccountStatus.ACTIVE)
        }
    }
}
```

- `accountQuery: AccountQuery` is **the read port the Account BC publishes** (`account/application/query/AccountQuery`) — it never touches the Account BC's write model (`AccountRepository`) or `domain/` (the Aggregate). This is the Anticorruption Layer (ACL) role: if the Account BC's internal model changes (the `Account` domain class, the `AccountStatus` enum), only `AccountAdapterImpl`'s mapping logic needs fixing, and the Card BC's `AccountView` is unaffected.
- Mapping is done with the `.let { }` scope function while preserving null-safety — if the Account isn't found (`AccountQuery.findAccounts` returns an empty list), `firstOrNull()` becomes `null` and it propagates as-is. The Account BC's exception type (`AccountNotFoundException`) never leaks into the Card layer.

```kotlin
// card/application/command/IssueCardService.kt — calling through the Adapter
@Service
class IssueCardService(
    private val cardRepository: CardRepository,
    private val accountAdapter: AccountAdapter,
) {
    fun issue(command: IssueCardCommand): IssueCardResult {
        val account = accountAdapter.findAccount(command.accountId, command.requesterId)
            ?: throw LinkedAccountNotFoundException()
        if (!account.active) throw CardIssueRequiresActiveAccountException()

        val card = Card.issue(accountId = command.accountId, ownerId = command.requesterId, brand = command.brand)
        cardRepository.saveCard(card)
        return IssueCardResult(/* ... */)
    }
}
```

Null-safety carries straight through into the Adapter call result too, as in `account?.active` — "account not found" and "account exists but isn't active" are promoted into two different domain exceptions (`LinkedAccountNotFoundException` vs `CardIssueRequiresActiveAccountException`), each answered with a different HTTP status (404/400).

## Registering the Spring bean — package scanning is enough

NestJS must explicitly register `{ provide: AccountAdapter, useClass: AccountAdapterImpl }` in `CardModule`'s `providers` array. In Kotlin/Spring, as long as `AccountAdapterImpl` is a `@Component` and lives under the `com.example.accountservice` subpackage tree, **component scanning registers it automatically**, and if there's a constructor requiring the `AccountAdapter` type, its sole implementation gets auto-injected — there's no separate module-registration file (see [module-pattern.md](module-pattern.md)).

One thing to watch for is when there end up being two or more implementations (e.g. a test Fake and the real implementation living in the same package) — in that case you need to disambiguate with `@Primary` or `@Qualifier`. Tests usually mock `AccountAdapter` with `@MockBean`/MockK, so this issue rarely arises in production code.

## Example 2 — Account → Card asynchronous Integration Event (actual code)

The reverse direction of communication from card issuance uses an Outbox-based Integration Event rather than a synchronous Adapter — there's no reason to hold Account's command response hostage until the Card BC's reaction (suspending/cancelling cards) completes, and Account must not even know that Card exists (see [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)).

**1) The Account side — converting an internal Domain Event into an externally published contract**

```kotlin
// account/application/integrationevent/AccountSuspendedIntegrationEventV1.kt
data class AccountSuspendedIntegrationEventV1(
    val accountId: String,
    val suspendedAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME
    companion object { const val EVENT_NAME = "account.suspended.v1" }
}
```

```kotlin
// account/application/event/AccountSuspendedEventHandler.kt
@Component
class AccountSuspendedEventHandler(
    private val notificationService: NotificationService,
    private val outboxWriter: OutboxWriter,
) {
    fun handle(event: AccountSuspendedEvent) {
        // Write the Integration Event that notifies external BCs (Card, etc.) in the same transaction.
        outboxWriter.saveAll(listOf(AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt.toString())))
        notificationService.sendEmail(/* ... */)
    }
}
```

The internal Domain Event (`AccountSuspendedEvent`, whose class name becomes the Outbox `eventType` as-is) is kept separate from the externally published contract (`AccountSuspendedIntegrationEventV1`, whose versioned `eventName` becomes the `eventType`) — this is so an internal Account refactor never breaks the contract with external BCs. The conversion point is always the EventHandler in `application/event/` (the Aggregate never creates an Integration Event directly).

**2) Shared infrastructure (outbox/) — determining eventType + routing**

```kotlin
// outbox/OutboxEvent.kt
this.eventType = (event as? IntegrationEventContract)?.eventName ?: (event::class.simpleName ?: "Unknown")
```

```kotlin
// outbox/EventHandlerRegistry.kt — eventType → handler Map + constructor injection (actual code)
@Component
class EventHandlerRegistry(
    /* ...Account Domain Event handlers... */
    private val cardIntegrationEventController: CardIntegrationEventController,
) {
    private val handlers: Map<String, (eventId: String, payload: String) -> Unit> =
        mapOf(
            /* ...AccountCreatedEvent, etc... */
            AccountSuspendedIntegrationEventV1.EVENT_NAME to { _, payload ->
                val event = objectMapper.readValue(payload, AccountSuspendedIntegrationEventV1::class.java)
                cardIntegrationEventController.onAccountSuspended(event.accountId)
            },
            AccountClosedIntegrationEventV1.EVENT_NAME to { _, payload -> /* ...onAccountClosed... */ },
        )

    fun dispatch(eventType: String, eventId: String, payload: String) {
        val handler = handlers[eventType]
        if (handler == null) {
            logger.warn("Unknown event type: {}", eventType)
            return
        }
        handler(eventId, payload)
    }
}
```

Since `outbox/` is shared infrastructure that belongs to no BC, it isn't a principle violation for `EventHandlerRegistry` to constructor-inject and reference both Account's Domain Event handlers and Card's Integration Event receiver — the only thing forbidden is **Account referencing Card** (no file inside the `account/` package imports anything from `card/`). Unlike java-springboot, kotlin-springboot doesn't use annotation-based auto-discovery — it uses this explicit constructor injection + `Map` literal approach instead. The tradeoff is that you can see, from this single file alone, exactly which eventType routes to which handler.

`EventHandlerRegistry` never touches `@Scheduled` or SQS directly — `OutboxConsumer` just hands the message it received from SQS off to this registry's `dispatch()`. And **the Domain Event → Integration Event conversion → reception by the external BC (Card) does not complete within one transaction or one call** (see the "asynchronous re-drain boundary" section of [domain-events.md](domain-events.md)) — once `AccountSuspendedEventHandler` writes the new Outbox row (`account.suspended.v1`) in step 1 above, that row is published to SQS on the next `OutboxPoller` tick (at most 1 second later), and `OutboxConsumer` receives it again and only then delivers it to `CardIntegrationEventController` via `EventHandlerRegistry.dispatch()`. In other words, after Account's command processing (the original transaction) finishes, at least two Poller/Consumer round trips (hundreds of ms to a few seconds) must elapse before the Card BC reacts.

**3) The Card side — receive, then call only its own use case**

```kotlin
// card/interfaces/integrationevent/CardIntegrationEventController.kt
@Component
class CardIntegrationEventController(
    private val suspendCardsByAccountService: SuspendCardsByAccountService,
    private val cancelCardsByAccountService: CancelCardsByAccountService,
) {
    fun onAccountSuspended(accountId: String) {
        suspendCardsByAccountService.suspend(accountId)
    }
    fun onAccountClosed(accountId: String) {
        cancelCardsByAccountService.cancel(accountId)
    }
}
```

This is an input adapter that lives in `interfaces/`, just like the HTTP `CardController`. It only takes `accountId` out of the Account Integration Event payload and calls its own domain's Command Service — it never references an Account BC class. Since `SuspendCardsByAccountService`/`CancelCardsByAccountService` only pick out ACTIVE (or ACTIVE·SUSPENDED) cards to change the status of, even if the same event is received again (at-least-once delivery), it idempotently does nothing.

## Principle summary

- **The interface is a Kotlin `interface`** — no need for NestJS's `abstract class` workaround.
- **"Not found" is `T?` + `?:`** — expressed without an `Optional` or a separate null check.
- **A synchronous Adapter is read-only** — never call an external BC's write method through an Adapter. If a write is needed, use an Integration Event.
- **Mapping is the Adapter implementation's responsibility** — it never returns the external BC's response model as-is; it converts to whatever shape the caller needs (`AccountView`).
- **Registration is delegated to component scanning** — just attach `@Component`, no separate explicit-binding file is needed.
- **Integration Event conversion is always handled by the EventHandler in `application/event/`** — the Aggregate never creates one directly.
- **`outbox/` is shared infrastructure** — it may reference handlers from multiple BCs, but the publishing BC (Account) must never reference the receiving BC (Card).
- **The receiving side must be idempotent** — assuming at-least-once delivery, a state change that's already been applied is silently ignored on re-receipt.

### Related documents

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — sync/async selection criteria (root, framework-agnostic)
- [domain-events.md](../../../../docs/architecture/domain-events.md) — the Outbox pattern, separating Domain Event/Integration Event
- [module-pattern.md](module-pattern.md) — component scanning and inter-package dependencies
- [layer-architecture.md](layer-architecture.md) — why an `interface` becomes the DI token, null-safety
- [domain-service.md](../../../../docs/architecture/domain-service.md) — the distinction from a Technical Service (encryption, etc.)
