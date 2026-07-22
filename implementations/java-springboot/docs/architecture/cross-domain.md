# Cross-Domain Call Pattern (Spring Boot)

> For the principles of when to choose synchronous (Adapter) vs. asynchronous (Integration Event), see the root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md). This document covers how to implement those two patterns in Spring.

## Current state of this repository

`examples/` has two Bounded Contexts, `account` and `card`. `notification` (a Technical Service, see [directory-structure.md](directory-structure.md)) is not a separate BC — it's a technical service placed inside `account`.

- **Card → Account (synchronous Adapter/ACL)**: when issuing a card, the linked account's existence and active status must be confirmed immediately within that request, so a synchronous Adapter pattern is used.
- **Account → Card (asynchronous Integration Event)**: suspending/closing an account must change the status of every linked card, but there's no reason this reflection should block the account command's response, and Account BC should be able to function even if Card BC doesn't know about it (or doesn't exist) — so this propagates via an Outbox-based Integration Event.

The two sections below each show this in actual code.

---

## Pattern 1 — synchronous Adapter (Card BC queries Account BC)

### Principles

1. **An Application Service never injects another BC's Service/Repository directly.** It only calls through an interface defined in its own `application/adapter/`.
2. **The Adapter interface is defined in the caller's (Card BC's) `application/adapter/`** — not the callee's (Account BC's). Whoever requires a particular shape defines the contract (the same dependency inversion as the Repository pattern).
3. **The Adapter implementation lives in the caller's (Card BC's) `infrastructure/`**, delegating by injecting the read-only interface (`AccountQuery`) that Account BC exposes.
4. **Never call another BC's write methods through an Adapter.** Only reads (ACL) are allowed — if a state change is needed, switch to an Integration Event (Pattern 2 below).

### Step 1 — define the interface in Card BC's `application/adapter/`

```java
// card/application/adapter/AccountAdapter.java — interface (owned by the caller)
package com.example.accountservice.card.application.adapter;

import java.util.Optional;

public interface AccountAdapter {

    Optional<AccountView> findAccount(String accountId, String ownerId);

    // A minimal account view owned by Card BC — rather than exposing Account BC's
    // AccountStatus enum directly, it's translated into active(boolean). Preventing
    // upstream (Account) model changes from leaking into the Card domain is the
    // purpose of the ACL.
    record AccountView(String accountId, boolean active) {}
}
```

### Step 2 — write the implementation in Card BC's `infrastructure/`

```java
// card/infrastructure/AccountAdapterImpl.java — implementation (owned by the caller)
package com.example.accountservice.card.infrastructure;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.card.application.adapter.AccountAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AccountAdapterImpl implements AccountAdapter {

    private final AccountQuery accountQuery;   // the read-only interface Account BC exposes

    @Override
    public Optional<AccountView> findAccount(String accountId, String ownerId) {
        return accountQuery.findAccounts(new AccountFindQuery(0, 1, accountId, ownerId, null))
                .accounts().stream().findFirst()
                .map(account -> new AccountView(account.getAccountId(), account.getStatus() == AccountStatus.ACTIVE));
    }
}
```

- **Only the Adapter implementation (`infrastructure/`) injects Account BC's actual interface (`AccountQuery`)** — since `AccountQuery` is a Spring bean (implemented by `AccountRepositoryImpl`), no separate DI configuration is needed for a `@Component` to inject it via its constructor (it's auto-bound by type as long as it's in the same `ApplicationContext` — see [module-pattern.md](module-pattern.md)).
- Account BC signals "account not found" via `Optional.empty()` rather than an exception, so that signal is translated directly into the `Optional.empty()` that Card understands — none of Account BC's exception types leak into the Card domain.

### Step 3 — use the Adapter from Card BC's Application Service

```java
// card/application/command/IssueCardService.java
@Service
@RequiredArgsConstructor
public class IssueCardService {

    private final CardRepository cardRepository;
    private final AccountAdapter accountAdapter;   // depends on the interface, not the concrete type (AccountAdapterImpl)

    public IssueCardResult issue(IssueCardCommand command) {
        AccountAdapter.AccountView account = accountAdapter.findAccount(command.accountId(), command.requesterId())
                .orElseThrow(() -> new CardException(
                        CardException.ErrorCode.LINKED_ACCOUNT_NOT_FOUND, "Could not find the account to link."));
        if (!account.active()) {
            throw new CardException(
                    CardException.ErrorCode.CARD_ISSUE_REQUIRES_ACTIVE_ACCOUNT, "A card can only be issued for an active account.");
        }

        Card card = Card.issue(command.accountId(), command.requesterId(), command.brand());
        cardRepository.saveCard(card);
        return new IssueCardResult(card.getCardId(), card.getAccountId(), card.getOwnerId(),
                card.getBrand(), card.getStatus().name(), card.getCreatedAt());
    }
}
```

- `IssueCardService` depends only on the `AccountAdapter` **interface** — it has no knowledge of `AccountAdapterImpl`, let alone `AccountQuery`, existing at all.
- In tests, mocking `AccountAdapter` with Mockito allows unit-testing `IssueCardService` without Account BC (see `card/application/command/IssueCardServiceTest.java`).

### Why the interface is necessary

- **Prevents dependency-direction contamination**: if Card BC's Application layer imported Account BC's concrete types, an internal structure change in Account BC would break Card BC's compilation. The `AccountAdapter` interface breaks this coupling.
- **Blocks unnecessary exposure**: of all the methods `AccountQuery` has, Card BC needs exactly one (`findAccounts`, doing a single-record lookup via `take: 1`). The Adapter interface exposes only that one.
- **Test isolation**: mocking `AccountAdapter` allows Card BC unit tests without bootstrapping Account BC (and its Repository, DB access).

---

## Pattern 2 — asynchronous Integration Event (Account BC → Card BC)

When an account is suspended/closed, every linked card's status must change, but there's no reason this reflection should block the account command's response (eventual consistency is enough), nor does Account BC need to know Card BC exists. This kind of "propagate a state change to another BC" case is the signal to use an Outbox-based Integration Event rather than an Adapter (see [domain-events.md](domain-events.md)).

### Step 1 — Account BC defines the public contract (Integration Event)

```java
// account/application/integrationevent/AccountSuspendedIntegrationEventV1.java
package com.example.accountservice.account.application.integrationevent;

import java.time.LocalDateTime;

public record AccountSuspendedIntegrationEventV1(String accountId, LocalDateTime suspendedAt) {
    public static final String EVENT_TYPE = "account.suspended.v1";
}
```

This is a class separate from the internal Domain Event (`AccountSuspendedEvent`) — since its name, schema, and version (`.v1`) form the externally published contract, this contract is only ever changed explicitly, even if the internal Domain Event's fields change.

### Step 2 — Account BC's existing Domain Event handler converts it and writes it to the Outbox

```java
// account/application/event/AccountSuspendedEventHandler.java (excerpt)
@Override
public void handle(String payload) throws Exception {
    AccountSuspendedEvent event = objectMapper.readValue(payload, AccountSuspendedEvent.class);

    // Write the Integration Event that notifies other BCs (e.g. Card) into the same Outbox transaction.
    outboxWriter.save(AccountSuspendedIntegrationEventV1.EVENT_TYPE,
            new AccountSuspendedIntegrationEventV1(event.accountId(), event.suspendedAt()));

    // Notification is best-effort — a failure here must not end this handler with a throw.
    // Throwing would cause this outbox row to be redrained, duplicating the Integration Event
    // above (harmless since the receiver is idempotent, but unnecessary amplification is avoided).
    try {
        notificationService.sendEmail(/* ... */);
    } catch (Exception e) {
        log.error("Failed to send suspension notification", e);
    }
}
```

The EventHandler in `application/event/` is the sole exception allowed to use `OutboxWriter` directly — the Aggregate (`Account`) never creates an Integration Event itself. The conversion point is always this EventHandler.

### Step 3 — Card BC receives it by implementing `OutboxEventHandler`

```java
// card/application/event/AccountSuspendedIntegrationEventHandler.java
@Component
@RequiredArgsConstructor
public class AccountSuspendedIntegrationEventHandler implements OutboxEventHandler {

    private final SuspendCardsByAccountService suspendCardsByAccountService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "account.suspended.v1";
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountIntegrationEventPayload event = objectMapper.readValue(payload, AccountIntegrationEventPayload.class);
        suspendCardsByAccountService.suspend(new SuspendCardsByAccountCommand(event.accountId()));
    }
}
```

`OutboxConsumer` (shared infrastructure, the `outbox/` package) has Spring automatically collect and constructor-inject `List<OutboxEventHandler> eventHandlers` — **Account BC never imports Card BC at all.** As long as Card BC implements `OutboxEventHandler` as a `@Component` and returns `"account.suspended.v1"` from `eventType()`, `OutboxConsumer` routes to it automatically based on the event-type string (the SQS MessageAttribute). This is the Spring idiom that replaces the nestjs implementation's `EventHandlerRegistry.register()` (explicit registration) — automatic Bean scanning does the registering instead.

- `AccountIntegrationEventPayload` is a local view (record) owned by Card BC — it never imports Account BC's Integration Event class, and only reads the `accountId` field it needs.
- `SuspendCardsByAccountService.suspend()` only selects and suspends the **ACTIVE cards** of the given account, so it stays idempotent even if the same event is re-received under at-least-once delivery.
- The `account.suspended.v1` row newly written by `AccountSuspendedEventHandler` (the Domain Event handler) is **not processed immediately within the same call** — it's published to SQS on `OutboxPoller`'s next polling tick (up to 1 second later), and only once `OutboxConsumer` receives it does Card BC's `AccountSuspendedIntegrationEventHandler` actually get called. In other words, at the moment `SuspendAccountService.suspend()` returns, Card BC's reaction has not yet happened — it's fully asynchronous (see [domain-events.md](domain-events.md)).

### Related code

- `card/application/adapter/AccountAdapter.java` / `card/infrastructure/AccountAdapterImpl.java` — Pattern 1
- `account/application/integrationevent/AccountSuspendedIntegrationEventV1.java`, `AccountClosedIntegrationEventV1.java` — the contracts Account BC publishes
- `account/application/event/AccountSuspendedEventHandler.java`, `AccountClosedEventHandler.java` — Domain Event → Integration Event conversion
- `card/application/event/AccountSuspendedIntegrationEventHandler.java`, `AccountClosedIntegrationEventHandler.java` — the Pattern 2 receiver
- `card/interfaces/rest/CardControllerE2ETest.java` — an E2E test that verifies both patterns via the actual HTTP API

---

## Harness verification

`harness/src/rules/NoCrossBcRepositoryInApplication.java` (rule: `no-cross-bc-repository-in-application`) fails the build if a file in one domain's `application/` directly imports another domain's `domain/*Repository` or `application/query/*Query` interface — it determines each file's owning domain from its path, and flags it if the imported type belongs to a different domain. An Adapter implementation like `payment/infrastructure/PaymentAccountAdapterImpl.java` injecting another domain's Query interface from `infrastructure/` (the ACL pattern itself) is not checked — what this rule blocks is `application/` bypassing the Adapter and referencing another BC's Repository/Query directly.

---

### Related documents

- [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — criteria for choosing synchronous (Adapter) vs. asynchronous (Integration Event), Context Map mapping
- [module-pattern.md](module-pattern.md) — the mechanism by which Spring binds an implementation to an interface-typed injection point
- [domain-events.md](domain-events.md) — Domain Event/Outbox/Integration Event, idempotency
- [directory-structure.md](directory-structure.md) — why `notification` is a Technical Service and not a separate BC
