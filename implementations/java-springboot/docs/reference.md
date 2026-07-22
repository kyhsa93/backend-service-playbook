# Practical Implementation Template (Java Spring Boot)

A template implementing the entire `Account` domain as this architecture's **correct target state**. Start by copying this template when adding a new domain.

> **Making the difference from `examples/` explicit.** This document shows the canonical pattern defined by `docs/architecture/`. `examples/` (the current code) still has the "known gaps" spelled out in [java-springboot/CLAUDE.md](../CLAUDE.md) (IDs including hyphens, synchronous event publishing without an Outbox, the Query Service directly using the Repository, a 2-field error response, no authentication, no migration tool, etc). The template below corrects all of those gaps — except that **the Domain layer directly carrying JPA annotations** is kept the same as `examples/`. That's because [layer-architecture.md](architecture/layer-architecture.md) explicitly acknowledges this as "a known architectural tension in this repository" and states that at the current scale (8 fields, 6 methods) it does not force an immediate separation — a tradeoff shown here as-is, not hidden.

---

## Directory structure

```
com.example.accountservice/
  AccountServiceApplication.java       ← @SpringBootApplication, @EnableConfigurationProperties, @EnableScheduling

  common/                              ← domain-agnostic shared code (see shared-modules.md)
    IdGenerator.java                   ← a pure utility, no framework dependency — aggregate-id.md
    web/
      GlobalExceptionHandler.java      ← @RestControllerAdvice — error-handling.md
      CorrelationIdFilter.java         ← Filter + MDC — cross-cutting-concerns.md

  config/                              ← dedicated to @ConfigurationProperties records — config.md
    JwtProperties.java

  account/
    domain/                            ← in principle, no framework dependency (also carrying JPA is a known gap, see the note above)
      Account.java                     ← Aggregate Root — also an @Entity
      Transaction.java                 ← child Entity — also an @Entity
      Money.java                       ← Value Object — an @Embeddable record
      AccountStatus.java               ← status enum
      TransactionType.java             ← transaction type enum
      AccountFindQuery.java            ← a record for dynamic query conditions
      AccountException.java            ← the domain exception + a nested ErrorCode enum
      AccountRepository.java           ← the Repository interface (a plain interface)
      AccountCreatedEvent.java         ← Domain Event (record)
      MoneyDepositedEvent.java
      MoneyWithdrawnEvent.java
      AccountClosedEvent.java

    application/
      command/
        CreateAccountService.java      ← @Service @Transactional
        DepositService.java
        WithdrawService.java
        CloseAccountService.java
        CreateAccountCommand.java      ← record
        DepositCommand.java
        WithdrawCommand.java
        CloseAccountCommand.java
        CreateAccountResult.java       ← record
        TransactionResult.java
      query/
        AccountQuery.java              ← the Query interface (a plain interface — separate from the Repository)
        GetAccountService.java         ← @Service @Transactional(readOnly = true)
        GetTransactionsService.java
        GetAccountResult.java          ← record
        GetTransactionsResult.java
      event/
        AccountCreatedEventHandler.java     ← an OutboxEventHandler implementation (one per event type)
        MoneyDepositedEventHandler.java
        MoneyWithdrawnEventHandler.java
        AccountSuspendedEventHandler.java
        AccountReactivatedEventHandler.java
        AccountClosedEventHandler.java

    infrastructure/
      persistence/
        AccountJpaRepository.java      ← extends JpaRepository<Account, Long>
        TransactionJpaRepository.java
        AccountRepositoryImpl.java     ← @Repository @Transactional — the AccountRepository implementation (includes the Outbox save)
        AccountQueryImpl.java          ← @Repository — the AccountQuery implementation (a projection query)

  outbox/                              ← domain-agnostic shared infrastructure (shared-modules.md) — not Account-specific
    OutboxEvent.java                   ← @Entity — the Outbox table
    OutboxEventJpaRepository.java
    OutboxEventHandler.java            ← the interface each event type's Handler implements
    OutboxWriter.java                  ← loads an event as an Outbox row inside the Repository.save() transaction
    OutboxPoller.java                  ← @Scheduled(fixedDelay=1000) — polls the Outbox table and publishes to SQS (see domain-events.md)
    OutboxConsumer.java                ← SmartLifecycle — receives from SQS and routes to an OutboxEventHandler (see domain-events.md)

    interfaces/
      rest/
        AccountController.java         ← @RestController
        CreateAccountRequest.java      ← record — an Interface DTO
        DepositRequest.java
        WithdrawRequest.java
        ErrorResponse.java             ← record — 4 fields
```

Tests mirror the same package under `src/test/java/com/example/accountservice/` (the standard Gradle source set). See [testing.md](architecture/testing.md) for detailed placement and examples.

---

## Domain layer

### Aggregate Root

```java
// account/domain/Account.java
package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                       // the JPA surrogate key — not the domain identifier, never exposed externally

    @Column(nullable = false, unique = true)
    private String accountId;              // the domain identifier — 32-character hex, no hyphens

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String email;

    @Embedded
    private Money balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime deletedAt;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    @Transient
    private final List<Transaction> pendingTransactions = new ArrayList<>();

    protected Account() {}   // the no-arg constructor JPA requires — protected blocks direct external construction

    public static Account create(String ownerId, String email, String currency) {
        Account account = new Account();
        account.accountId = IdGenerator.generate();   // 32-character hex, no hyphens — see aggregate-id.md
        account.ownerId = ownerId;
        account.email = email;
        account.balance = new Money(0, currency);
        account.status = AccountStatus.ACTIVE;
        account.createdAt = LocalDateTime.now();
        account.updatedAt = account.createdAt;
        account.domainEvents.add(new AccountCreatedEvent(account.accountId, ownerId, email, currency, account.createdAt));
        return account;
    }

    public Transaction deposit(long amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT, "Only an active account can receive deposits.");
        }
        if (amount <= 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_AMOUNT, "Amount must be greater than 0.");
        }
        Money money = new Money(amount, this.balance.currency());
        this.balance = this.balance.add(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction = Transaction.create(this.accountId, TransactionType.DEPOSIT, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(new MoneyDepositedEvent(
                this.accountId, this.email, transaction.getTransactionId(), money, this.balance, transaction.getCreatedAt()));
        return transaction;
    }

    public Transaction withdraw(long amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT, "Only an active account can make withdrawals.");
        }
        Money money = new Money(amount, this.balance.currency());
        if (this.balance.isLessThan(money)) {
            throw new AccountException(AccountException.ErrorCode.INSUFFICIENT_BALANCE, "Insufficient balance.");
        }
        this.balance = this.balance.subtract(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction = Transaction.create(this.accountId, TransactionType.WITHDRAWAL, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(new MoneyWithdrawnEvent(
                this.accountId, this.email, transaction.getTransactionId(), money, this.balance, transaction.getCreatedAt()));
        return transaction;
    }

    // Account "closing" (a state transition) — a concept distinct from deletion (delete), see persistence.md
    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new AccountException(AccountException.ErrorCode.ACCOUNT_ALREADY_CLOSED, "The account is already closed.");
        }
        if (!this.balance.isZero()) {
            throw new AccountException(AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO, "An account with a non-zero balance cannot be closed.");
        }
        this.status = AccountStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(new AccountClosedEvent(this.accountId, this.email, this.updatedAt));
    }

    // Deletion — only a closed account can be soft-deleted (a corrected form of the repository-pattern.md/persistence.md gap)
    public void delete() {
        if (this.status != AccountStatus.CLOSED) {
            throw new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_CLOSABLE_FOR_DELETE, "Only a closed account can be deleted.");
        }
        this.deletedAt = LocalDateTime.now();
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public List<Transaction> pullPendingTransactions() {
        List<Transaction> transactions = new ArrayList<>(this.pendingTransactions);
        this.pendingTransactions.clear();
        return transactions;
    }

    public String getAccountId() { return accountId; }
    public String getOwnerId() { return ownerId; }
    public String getEmail() { return email; }
    public Money getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
}
```

### Child Entity

```java
// account/domain/Transaction.java
package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;          // the domain identifier — the basis for equality

    @Column(nullable = false)
    private String accountId;              // the ID of the owning Aggregate Root (a reference)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Embedded
    private Money amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Transaction() {}

    // package-private static — blocks direct construction that bypasses Account, at compile time
    static Transaction create(String accountId, TransactionType type, Money amount) {
        Transaction transaction = new Transaction();
        transaction.transactionId = IdGenerator.generate();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }

    public String getTransactionId() { return transactionId; }
    public TransactionType getType() { return type; }
    public Money getAmount() { return amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

### Value Object

```java
// account/domain/Money.java
package com.example.accountservice.account.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public record Money(long amount, String currency) {

    public Money {   // compact canonical constructor — always validates on construction
        if (amount < 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "Amount must be 0 or greater.");
        }
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount - other.amount, this.currency);
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount < other.amount;
    }

    public boolean isZero() {
        return this.amount == 0;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new AccountException(AccountException.ErrorCode.CURRENCY_MISMATCH, "Currency mismatch.");
        }
    }
}
```

### Status enum

```java
// account/domain/AccountStatus.java
package com.example.accountservice.account.domain;

public enum AccountStatus {
    ACTIVE, SUSPENDED, CLOSED
}

// account/domain/TransactionType.java
package com.example.accountservice.account.domain;

public enum TransactionType {
    DEPOSIT, WITHDRAWAL
}
```

### Domain Event

```java
// account/domain/AccountCreatedEvent.java
package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record AccountCreatedEvent(
        String accountId, String ownerId, String email, String currency, LocalDateTime occurredAt) {}

// account/domain/MoneyDepositedEvent.java
public record MoneyDepositedEvent(
        String accountId, String email, String transactionId,
        Money amount, Money balanceAfter, LocalDateTime occurredAt) {}
```

### Repository interface — a plain `interface`, located in domain/

```java
// account/domain/AccountRepository.java
package com.example.accountservice.account.domain;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);
    List<Account> findAll(AccountFindQuery query);
    long countAll(AccountFindQuery query);
    void save(Account account);
    void delete(String accountId);          // a soft delete — corrects the repository-pattern.md gap
    List<Transaction> findTransactions(String accountId, int page, int take);
    long countTransactions(String accountId);
}

// account/domain/AccountFindQuery.java — dynamic query conditions
public record AccountFindQuery(int page, int take, String accountId, String ownerId, List<String> status) {}
```

A Java `interface` is itself a valid DI type at runtime, so there's no need for the `abstract class` workaround TypeScript needs. Spring automatically binds the sole implementation on the classpath (`AccountRepositoryImpl`, `@Repository`) to every injection point of this interface type.

---

## Application layer

### Command Service — delegates to static factories, never performs business logic itself

```java
// application/command/CreateAccountService.java
package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAccountService {

    private final AccountRepository accountRepository;

    public CreateAccountResult create(CreateAccountCommand command) {
        // Business logic is delegated to the Aggregate — the Service only coordinates
        Account account = Account.create(command.ownerId(), command.email(), command.currency());
        // Inside Repository.saveAccount() (@Transactional), Account + Outbox are saved in the same transaction (see domain-events.md)
        accountRepository.saveAccount(account);
        // Returns right after saving — Outbox draining is handled by a separate process (OutboxPoller/OutboxConsumer)
        return new CreateAccountResult(account.getAccountId(), account.getOwnerId(), account.getBalance().amount(), account.getBalance().currency());
    }
}

// application/command/DepositService.java
@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;

    public TransactionResult deposit(DepositCommand command) {
        Account account = accountRepository
                .findAccounts(new AccountFindQuery(0, 1, command.accountId(), command.requesterId(), null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));

        var transaction = account.deposit(command.amount());
        accountRepository.saveAccount(account);

        return new TransactionResult(transaction.getTransactionId(), transaction.getType().name(), transaction.getAmount().amount(), transaction.getCreatedAt());
    }
}
```

**The Command Service does not call `ApplicationEventPublisher.publishEvent()`** — `examples/` already implements this pattern for real ([domain-events.md](architecture/domain-events.md)). The event is saved to the Outbox inside `Repository.saveAccount()` (see the Infrastructure section below), and the Command Service returns right after saving — Outbox draining is handled later (up to 1 second later), not by an `OutboxRelay` but by two entirely separate processes: `OutboxPoller` (`@Scheduled` polling) / `OutboxConsumer` (SQS receiving).

### Query interface — separate from the Repository (corrects the cqrs-pattern.md gap)

```java
// application/query/AccountQuery.java
package com.example.accountservice.account.application.query;

import java.util.List;
import java.util.Optional;

public interface AccountQuery {
    Optional<GetAccountResult> getAccount(String accountId, String ownerId);
    List<TransactionSummary> getTransactions(String accountId, int page, int take);
    long countTransactions(String accountId);
}
```

### Query Service — uses only `AccountQuery`, never references the write-side `AccountRepository`

```java
// application/query/GetAccountService.java
package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {

    private final AccountQuery accountQuery;   // ← the Query interface, not the Repository

    public GetAccountResult getAccount(String accountId, String requesterId) {
        return accountQuery.getAccount(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
    }
}
```

`@Transactional(readOnly = true)` lets Hibernate skip dirty checking/flush, reducing read overhead.

### Command / Result — `record`

```java
// application/command/DepositCommand.java
public record DepositCommand(String accountId, String requesterId, long amount) {}

// application/command/CreateAccountCommand.java
public record CreateAccountCommand(String ownerId, String email, String currency) {}

// application/command/CreateAccountResult.java
public record CreateAccountResult(String accountId, String ownerId, long balance, String currency) {}

// application/command/TransactionResult.java
import java.time.LocalDateTime;
public record TransactionResult(String transactionId, String type, long amount, LocalDateTime createdAt) {}

// application/query/GetAccountResult.java
public record GetAccountResult(String accountId, String ownerId, String email, long balance, String currency, String status) {}

// application/query/GetTransactionsResult.java
import java.util.List;
public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(String transactionId, String type, long amount, java.time.LocalDateTime createdAt) {}
}
```

### Domain Event Handler — via the Outbox (not an in-process event bus)

Inside the Repository's save transaction, `OutboxWriter` loads the domain event into the `outbox` table as well. The Command Service returns right after saving, and draining is handled by an entirely separate process — `OutboxPoller` (`@Scheduled(fixedDelay=1000)`) publishes unprocessed events to SQS, and `OutboxConsumer` (a SmartLifecycle background thread) receives from SQS and calls the Handler for each event type. See domain-events.md for details.

```java
// application/event/AccountCreatedEventHandler.java
package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.AccountCreatedEvent;
import com.example.accountservice.account.application.service.NotificationService;
import com.example.accountservice.outbox.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountCreatedEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountCreatedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountCreatedEvent event = objectMapper.readValue(payload, AccountCreatedEvent.class);
        notificationService.sendEmail(event.accountId(), "AccountCreated", event.email(),
                "[Account] Your account has been opened",
                "Account (" + event.accountId() + ") has been opened. Currency: " + event.currency());
    }
}
```

The responsibility for logging on a publish failure and leaving `processed` unupdated for retry belongs to `outbox/OutboxPoller`, not to individual Handlers. The responsibility for leaving an SQS message undeleted for retry (at-least-once) when a Handler fails after receiving belongs to `outbox/OutboxConsumer` — in both cases, so the same try-catch doesn't need to be repeated in every Handler.

---

## Infrastructure layer

### Query implementation — projection without loading the whole Aggregate

```java
// infrastructure/persistence/AccountQueryImpl.java
package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.application.query.GetAccountResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AccountQueryImpl implements AccountQuery {

    private final EntityManager em;

    @Override
    public Optional<GetAccountResult> getAccount(String accountId, String ownerId) {
        String jpql = """
                SELECT new com.example.accountservice.account.application.query.GetAccountResult(
                    a.accountId, a.ownerId, a.email, a.balance.amount, a.balance.currency, a.status)
                FROM Account a
                WHERE a.accountId = :accountId AND a.ownerId = :ownerId AND a.deletedAt IS NULL
                """;
        return em.createQuery(jpql, GetAccountResult.class)
                .setParameter("accountId", accountId)
                .setParameter("ownerId", ownerId)
                .getResultStream().findFirst();
    }
}
```

The Query path does not load the whole `Account` Aggregate (including the associated `Money` embeddable) — it only queries the columns matching the response schema. See "Why separate it this way" in [cqrs-pattern.md](architecture/cqrs-pattern.md).

### Repository implementation — the Aggregate + child Entity + Outbox in one transaction

```java
// infrastructure/persistence/AccountRepositoryImpl.java
package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.*;
import com.example.accountservice.account.infrastructure.outbox.OutboxEvent;
import com.example.accountservice.account.infrastructure.outbox.OutboxEventJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {

    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final OutboxEventJpaRepository outboxJpaRepository;
    private final EntityManager em;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId);
    }

    @Override
    public List<Account> findAll(AccountFindQuery query) {
        String jpql = buildJpql(query, false);
        var q = em.createQuery(jpql, Account.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        return q.getResultList();
    }

    @Override
    @Transactional   // Saving Account + saving the child Entity + saving the Outbox are one physical transaction
    public void save(Account account) {
        jpaRepository.save(account);

        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(pending);
        }

        // Save the Domain Event into the Outbox table as well — the Command Service no longer calls publishEvent()
        List<Object> events = account.pullDomainEvents();
        if (!events.isEmpty()) {
            List<OutboxEvent> outboxEvents = events.stream()
                    .map(e -> OutboxEvent.from(e, objectMapper))
                    .toList();
            outboxJpaRepository.saveAll(outboxEvents);
        }
    }

    @Override
    @Transactional
    public void delete(String accountId) {
        jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId).ifPresent(account -> {
            account.delete();          // validates invariants via a domain method, then sets deletedAt
            jpaRepository.save(account);
        });
    }

    private String buildJpql(AccountFindQuery query, boolean count) {
        StringBuilder sb = new StringBuilder(count
                ? "SELECT COUNT(a) FROM Account a WHERE a.deletedAt IS NULL"
                : "SELECT a FROM Account a WHERE a.deletedAt IS NULL");
        if (query.accountId() != null && !query.accountId().isBlank()) sb.append(" AND a.accountId = :accountId");
        if (query.ownerId() != null && !query.ownerId().isBlank()) sb.append(" AND a.ownerId = :ownerId");
        if (query.status() != null && !query.status().isEmpty()) sb.append(" AND a.status IN :status");
        if (!count) sb.append(" ORDER BY a.accountId DESC");
        return sb.toString();
    }
}
```

```java
// infrastructure/persistence/AccountJpaRepository.java — Spring Data auto-generates the implementation
public interface AccountJpaRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountIdAndOwnerIdAndDeletedAtIsNull(String accountId, String ownerId);
    Optional<Account> findByAccountIdAndDeletedAtIsNull(String accountId);
}
```

### Outbox — an event store bound atomically to the Aggregate save

```java
// infrastructure/outbox/OutboxEvent.java
package com.example.accountservice.account.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected OutboxEvent() {}

    public static OutboxEvent from(Object domainEvent, ObjectMapper objectMapper) {
        try {
            OutboxEvent event = new OutboxEvent();
            event.eventId = UUID.randomUUID().toString().replace("-", "");
            event.eventType = domainEvent.getClass().getSimpleName();
            event.payload = objectMapper.writeValueAsString(domainEvent);
            event.createdAt = LocalDateTime.now();
            return event;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }

    public void markProcessed() { this.processed = true; }
}
```

```java
// outbox/OutboxPoller.java — actual code (excerpt). Publishes to SQS after @Scheduled polling
package com.example.accountservice.outbox;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private final OutboxEventJpaRepository outboxJpaRepository;
    private final SqsClient sqsClient;

    @Scheduled(fixedDelay = 1000)   // re-runs 1 second after the previous poll finishes
    @Transactional   // payload is an @Lob column, so the query and iteration must happen inside the same transaction (see domain-events.md)
    public void poll() {
        var pending = outboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                sqsClient.sendMessage(/* publishes eventType as a MessageAttribute, payload as the body */);
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish to SQS: eventId={}", event.getEventId(), e);
                // processed is left unupdated, so it's retried on the next poll
            }
        }
    }
}
```

`OutboxConsumer` (`SmartLifecycle`, a dedicated background thread) receives from this SQS queue via long polling and finds/calls the `OutboxEventHandler` implementation by `eventType` — the Command Service references neither of these two. See [domain-events.md](architecture/domain-events.md) for details.

`@EnableScheduling` must be declared on `AccountServiceApplication` for `OutboxPoller.poll()`'s `@Scheduled` method to run (see [scheduling.md](architecture/scheduling.md)).

---

## Interfaces layer

### Controller — extracts the authenticated user's info from `Authentication`

```java
// interfaces/rest/AccountController.java
package com.example.accountservice.account.interfaces.rest;

import com.example.accountservice.account.application.command.*;
import com.example.accountservice.account.application.query.GetAccountResult;
import com.example.accountservice.account.application.query.GetAccountService;
import com.example.accountservice.account.application.query.GetTransactionsResult;
import com.example.accountservice.account.application.query.GetTransactionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final CreateAccountService createAccountService;
    private final DepositService depositService;
    private final WithdrawService withdrawService;
    private final CloseAccountService closeAccountService;
    private final GetAccountService getAccountService;
    private final GetTransactionsService getTransactionsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateAccountResult createAccount(Authentication authentication, @Valid @RequestBody CreateAccountRequest request) {
        return createAccountService.create(new CreateAccountCommand(authentication.getName(), request.email(), request.currency()));
    }

    @PostMapping("/{accountId}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResult deposit(Authentication authentication, @PathVariable String accountId, @RequestBody DepositRequest request) {
        return depositService.deposit(new DepositCommand(accountId, authentication.getName(), request.amount()));
    }

    @PostMapping("/{accountId}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResult withdraw(Authentication authentication, @PathVariable String accountId, @RequestBody WithdrawRequest request) {
        return withdrawService.withdraw(new WithdrawCommand(accountId, authentication.getName(), request.amount()));
    }

    @PostMapping("/{accountId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(Authentication authentication, @PathVariable String accountId) {
        closeAccountService.close(new CloseAccountCommand(accountId, authentication.getName()));
    }

    @GetMapping("/{accountId}")
    public GetAccountResult getAccount(Authentication authentication, @PathVariable String accountId) {
        return getAccountService.getAccount(accountId, authentication.getName());
    }

    @GetMapping("/{accountId}/transactions")
    public GetTransactionsResult getTransactions(
            Authentication authentication,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int take
    ) {
        return getTransactionsService.getTransactions(accountId, authentication.getName(), page, take);
    }
}
```

The current code in `examples/` trusts an unauthenticated value as-is via `@RequestHeader("X-User-Id")` (a known gap, see [authentication.md](architecture/authentication.md)) — this template shows the target state, extracting `userId` (the subject) from the `Authentication` that Spring Security's `SecurityFilterChain` has already verified. An `@ExceptionHandler` for `AccountException` can also be placed inside the Controller while there's only one domain, but moving it to a global `@RestControllerAdvice` as below means no duplicate definition is needed once a second domain is added.

### Global exception handling — `@RestControllerAdvice`, a 4-field response

```java
// common/web/GlobalExceptionHandler.java
package com.example.accountservice.common.web;

import com.example.accountservice.account.domain.AccountException;
import com.example.accountservice.account.interfaces.rest.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
        HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        log.warn("Account request failed: code={}, message={}", e.code(), e.getMessage());
        return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message));
    }
}
```

### Error response — 4 fields

```java
// interfaces/rest/ErrorResponse.java
package com.example.accountservice.account.interfaces.rest;

import org.springframework.http.HttpStatus;

public record ErrorResponse(int statusCode, String code, String message, String error) {
    public static ErrorResponse of(HttpStatus status, String code, String message) {
        return new ErrorResponse(status.value(), code, message, status.getReasonPhrase());
    }
}
```

`examples/`'s current `ErrorResponse` has only the 2 fields `code`/`message` (a known gap, see [error-handling.md](architecture/error-handling.md)) — this template follows the 4-field format, including `statusCode`/`error` as well.

### Interface DTO — a thin wrapper that copies the Application object as-is

```java
// interfaces/rest/CreateAccountRequest.java
package com.example.accountservice.account.interfaces.rest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequest(@NotBlank @Email String email, @NotBlank String currency) {}

// interfaces/rest/DepositRequest.java
public record DepositRequest(long amount) {}

// interfaces/rest/WithdrawRequest.java
public record WithdrawRequest(long amount) {}
```

A single line in the Controller method is the mapping point: `new CreateAccountCommand(authentication.getName(), request.email(), request.currency())`. A separate mapping library (MapStruct, etc) is unnecessary until the field count grows large (see the "Interface DTO" section in [layer-architecture.md](architecture/layer-architecture.md)).

---

## Exception / ErrorCode

```java
// account/domain/AccountException.java
package com.example.accountservice.account.domain;

public class AccountException extends RuntimeException {

    public enum ErrorCode {
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INVALID_MONEY_AMOUNT,
        CURRENCY_MISMATCH,
        DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
        WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
        INSUFFICIENT_BALANCE,
        ACCOUNT_ALREADY_CLOSED,
        ACCOUNT_BALANCE_NOT_ZERO,
        ACCOUNT_NOT_CLOSABLE_FOR_DELETE
    }

    private final ErrorCode code;

    public AccountException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
```

---

## Configuration / Bootstrap

```java
// AccountServiceApplication.java
package com.example.accountservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class})
@EnableScheduling   // enables @Scheduled for OutboxPoller, etc — see scheduling.md
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

```java
// config/JwtProperties.java — fail-fast validation at startup, see config.md
package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(@NotBlank String secret, long expirationSeconds) {}
```

```java
// common/config/SecurityConfig.java — see authentication.md
package com.example.accountservice.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/auth/sign-in").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
```

For the detailed rationale behind each setting (why `@ConfigurationProperties`+`@Validated`, why `authorizeHttpRequests` is applied globally, etc), see [config.md](architecture/config.md) and [authentication.md](architecture/authentication.md) — this section only shows the final assembled form.
