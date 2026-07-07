# 실전 구현 템플릿 (Java Spring Boot)

`Account` 도메인 전체를 본 아키텍처의 **올바른 목표 상태**로 구현한 템플릿이다. 새 도메인을 추가할 때 이 템플릿을 복사해서 시작한다.

> **`examples/`와의 차이를 분명히 한다.** 이 문서는 `docs/architecture/`가 정의하는 정석 패턴을 보여준다. `examples/`(현재 코드)는 [java-springboot/CLAUDE.md](../CLAUDE.md)가 명시하는 "알려진 gap"(ID 하이픈 포함, Outbox 없이 동기 이벤트 발행, Query Service의 Repository 직접 사용, 에러 응답 2필드, 인증 없음, 마이그레이션 도구 부재 등)을 아직 갖고 있다. 아래 템플릿은 그 gap을 모두 교정한 형태다 — 다만 **Domain 레이어가 JPA 애노테이션을 직접 갖는 것**만은 `examples/`와 동일하게 유지한다. 이는 [layer-architecture.md](architecture/layer-architecture.md)가 "이 저장소의 알려진 아키텍처적 긴장(known tension)"으로 명시적으로 인정하고, 현재 규모(필드 8개, 메서드 6개)에서는 즉각적인 분리를 강제하지 않는다고 밝힌 트레이드오프이기 때문이다 — 여기서 이를 감추지 않고 그대로 보여준다.

---

## 디렉토리 구조

```
com.example.accountservice/
  AccountServiceApplication.java       ← @SpringBootApplication, @EnableConfigurationProperties, @EnableScheduling

  common/                              ← 도메인 무관 공유 코드 (shared-modules.md 참고)
    IdGenerator.java                   ← 순수 유틸, 프레임워크 무의존 — aggregate-id.md
    web/
      GlobalExceptionHandler.java      ← @RestControllerAdvice — error-handling.md
      CorrelationIdFilter.java         ← Filter + MDC — cross-cutting-concerns.md

  config/                              ← @ConfigurationProperties record 전용 — config.md
    JwtProperties.java

  account/
    domain/                            ← 프레임워크 무의존이 원칙 (JPA 겸용은 알려진 gap, 위 안내 참고)
      Account.java                     ← Aggregate Root — @Entity 겸용
      Transaction.java                 ← 하위 Entity — @Entity 겸용
      Money.java                       ← Value Object — @Embeddable record
      AccountStatus.java               ← 상태 enum
      TransactionType.java             ← 거래 유형 enum
      AccountFindQuery.java            ← 동적 조회 조건 record
      AccountException.java            ← 도메인 예외 + 중첩 ErrorCode enum
      AccountRepository.java           ← Repository 인터페이스 (plain interface)
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
        AccountQuery.java              ← Query 인터페이스 (plain interface — Repository와 별개)
        GetAccountService.java         ← @Service @Transactional(readOnly = true)
        GetTransactionsService.java
        GetAccountResult.java          ← record
        GetTransactionsResult.java
      event/
        AccountCreatedEventHandler.java     ← OutboxEventHandler 구현체 (이벤트 타입별로 하나씩)
        MoneyDepositedEventHandler.java
        MoneyWithdrawnEventHandler.java
        AccountSuspendedEventHandler.java
        AccountReactivatedEventHandler.java
        AccountClosedEventHandler.java

    infrastructure/
      persistence/
        AccountJpaRepository.java      ← JpaRepository<Account, Long> 확장
        TransactionJpaRepository.java
        AccountRepositoryImpl.java     ← @Repository @Transactional — AccountRepository 구현체 (Outbox 저장 포함)
        AccountQueryImpl.java          ← @Repository — AccountQuery 구현체 (projection 쿼리)

  outbox/                              ← 도메인 무관 공유 인프라(shared-modules.md) — Account 전용 아님
    OutboxEvent.java                   ← @Entity — Outbox 테이블
    OutboxEventJpaRepository.java
    OutboxEventHandler.java            ← 이벤트 타입별 Handler가 구현하는 인터페이스
    OutboxWriter.java                  ← Repository.save() 트랜잭션 안에서 이벤트를 Outbox 행으로 적재
    OutboxRelay.java                   ← Command Service가 저장 직후 동기 호출 — @Scheduled 폴링 아님(domain-events.md 참고)

    interfaces/
      rest/
        AccountController.java         ← @RestController
        CreateAccountRequest.java      ← record — Interface DTO
        DepositRequest.java
        WithdrawRequest.java
        ErrorResponse.java             ← record — 4필드
```

테스트는 `src/test/java/com/example/accountservice/`에 동일한 패키지를 미러링한다(Gradle 표준 소스셋). 상세 배치와 예시는 [testing.md](architecture/testing.md) 참고.

---

## Domain 레이어

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
    private Long id;                       // JPA 대리키 — 도메인 식별자 아님, 외부에 노출 금지

    @Column(nullable = false, unique = true)
    private String accountId;              // 도메인 식별자 — 32자리 hex, 하이픈 없음

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

    protected Account() {}   // JPA가 요구하는 기본 생성자 — protected로 외부 직접 생성 차단

    public static Account create(String ownerId, String email, String currency) {
        Account account = new Account();
        account.accountId = IdGenerator.generate();   // 32자리 hex, 하이픈 없음 — aggregate-id.md
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
            throw new AccountException(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT, "활성 상태의 계좌만 입금할 수 있습니다.");
        }
        if (amount <= 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_AMOUNT, "금액은 0보다 커야 합니다.");
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
            throw new AccountException(AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT, "활성 상태의 계좌만 출금할 수 있습니다.");
        }
        Money money = new Money(amount, this.balance.currency());
        if (this.balance.isLessThan(money)) {
            throw new AccountException(AccountException.ErrorCode.INSUFFICIENT_BALANCE, "잔액이 부족합니다.");
        }
        this.balance = this.balance.subtract(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction = Transaction.create(this.accountId, TransactionType.WITHDRAWAL, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(new MoneyWithdrawnEvent(
                this.accountId, this.email, transaction.getTransactionId(), money, this.balance, transaction.getCreatedAt()));
        return transaction;
    }

    // 계좌 "종료"(상태 전이) — 삭제(delete)와는 별개 개념, persistence.md 참고
    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new AccountException(AccountException.ErrorCode.ACCOUNT_ALREADY_CLOSED, "이미 종료된 계좌입니다.");
        }
        if (!this.balance.isZero()) {
            throw new AccountException(AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO, "잔액이 0이 아닌 계좌는 종료할 수 없습니다.");
        }
        this.status = AccountStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(new AccountClosedEvent(this.accountId, this.email, this.updatedAt));
    }

    // 삭제 — 종료된 계좌만 soft delete 가능 (repository-pattern.md/persistence.md의 gap을 교정한 형태)
    public void delete() {
        if (this.status != AccountStatus.CLOSED) {
            throw new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_CLOSABLE_FOR_DELETE, "종료된 계좌만 삭제할 수 있습니다.");
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

### 하위 Entity

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
    private String transactionId;          // 도메인 식별자 — 동등성 기준

    @Column(nullable = false)
    private String accountId;              // 소속 Aggregate Root의 ID (참조)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Embedded
    private Money amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Transaction() {}

    // package-private static — Account를 거치지 않은 직접 생성을 컴파일 타임에 차단
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

    public Money {   // compact canonical constructor — 생성 시 항상 검증
        if (amount < 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "금액은 0 이상이어야 합니다.");
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
            throw new AccountException(AccountException.ErrorCode.CURRENCY_MISMATCH, "통화가 일치하지 않습니다.");
        }
    }
}
```

### 상태 enum

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

### Repository 인터페이스 — plain `interface`, domain/에 위치

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
    void delete(String accountId);          // soft delete — repository-pattern.md의 gap을 교정
    List<Transaction> findTransactions(String accountId, int page, int take);
    long countTransactions(String accountId);
}

// account/domain/AccountFindQuery.java — 동적 조회 조건
public record AccountFindQuery(int page, int take, String accountId, String ownerId, List<String> status) {}
```

Java `interface`는 그 자체로 런타임에 유효한 DI 타입이므로, TypeScript의 `abstract class` 우회가 필요 없다. Spring이 이 인터페이스 타입 주입 지점에 classpath상 유일한 구현체(`AccountRepositoryImpl`, `@Repository`)를 자동 바인딩한다.

---

## Application 레이어

### Command Service — 정적 팩토리 위임, 비즈니스 로직 직접 수행 금지

```java
// application/command/CreateAccountService.java
package com.example.accountservice.account.application.command;

import com.example.accountservice.account.domain.Account;
import com.example.accountservice.account.domain.AccountRepository;
import com.example.accountservice.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateAccountService {

    private final AccountRepository accountRepository;
    private final OutboxRelay outboxRelay;

    public CreateAccountResult create(CreateAccountCommand command) {
        // 비즈니스 로직은 Aggregate에 위임 — Service는 조율만 한다
        Account account = Account.create(command.ownerId(), command.email(), command.currency());
        // Repository.save() 내부(@Transactional)에서 Account + Outbox를 같은 트랜잭션으로 저장 (domain-events.md)
        accountRepository.save(account);
        // 커밋 직후 동기적으로 Outbox를 드레인한다 — @Scheduled 폴링이 아니다
        outboxRelay.processPending();
        return new CreateAccountResult(account.getAccountId(), account.getOwnerId(), account.getBalance().amount(), account.getBalance().currency());
    }
}

// application/command/DepositService.java
@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;
    private final OutboxRelay outboxRelay;

    public TransactionResult deposit(DepositCommand command) {
        Account account = accountRepository.findByAccountIdAndOwnerId(command.accountId(), command.requesterId())
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));

        var transaction = account.deposit(command.amount());
        accountRepository.save(account);
        outboxRelay.processPending();

        return new TransactionResult(transaction.getTransactionId(), transaction.getType().name(), transaction.getAmount().amount(), transaction.getCreatedAt());
    }
}
```

**Command Service가 `ApplicationEventPublisher.publishEvent()`를 호출하지 않는다** — `examples/`가 이미 이 패턴을 실제로 구현하고 있다([domain-events.md](architecture/domain-events.md)). 이벤트는 `Repository.save()` 내부에서 Outbox로 저장되고(아래 Infrastructure 절 참고), Command Service는 저장 직후 `outboxRelay.processPending()`을 동기 호출해 드레인한다.

### Query 인터페이스 — Repository와 별개 (cqrs-pattern.md gap 교정)

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

### Query Service — `AccountQuery`만 사용, 쓰기용 `AccountRepository`를 참조하지 않는다

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

    private final AccountQuery accountQuery;   // ← Repository가 아니라 Query 인터페이스

    public GetAccountResult getAccount(String accountId, String requesterId) {
        return accountQuery.getAccount(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
    }
}
```

`@Transactional(readOnly = true)`는 Hibernate가 dirty checking/flush를 생략해 읽기 오버헤드를 줄인다.

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

### Domain Event Handler — Outbox 경유 (in-process 이벤트 버스 아님)

Repository의 저장 트랜잭션 안에서 `OutboxWriter`가 도메인 이벤트를 `outbox` 테이블에 함께 적재하고, Command Service가 저장이 끝난 직후 `OutboxRelay.processPending()`을 동기 호출해 미처리 이벤트를 전부 드레인한다. 실제 발송은 이벤트 타입별 Handler가 맡는다 — domain-events.md 참고.

```java
// application/event/AccountCreatedEventHandler.java
package com.example.accountservice.account.application.event;

import com.example.accountservice.account.domain.AccountCreatedEvent;
import com.example.accountservice.notification.application.service.NotificationService;
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
                "[Account] 계좌가 개설되었습니다",
                "계좌(" + event.accountId() + ")가 개설되었습니다. 통화: " + event.currency());
    }
}
```

실패 시 로그를 남기고 재시도를 위해 `processed`를 갱신하지 않는 책임은 개별 Handler가 아니라 `outbox/OutboxRelay`가 진다(모든 Handler에 동일한 try-catch를 반복하지 않기 위함) — `outbox/OutboxRelay.java` 참고.

---

## Infrastructure 레이어

### Query 구현체 — Aggregate 전체 로딩 없이 projection

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

Query 경로는 `Account` Aggregate 전체(연관된 `Money` 임베더블 포함)를 로딩하지 않고, 응답 스키마에 맞춘 컬럼만 조회한다 — [cqrs-pattern.md](architecture/cqrs-pattern.md) "왜 이렇게 분리하는가" 참고.

### Repository 구현체 — Aggregate + 하위 Entity + Outbox를 한 트랜잭션으로

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
    @Transactional   // Account 저장 + 하위 Entity 저장 + Outbox 저장이 하나의 물리 트랜잭션
    public void save(Account account) {
        jpaRepository.save(account);

        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(pending);
        }

        // Domain Event를 Outbox 테이블에 함께 저장 — Command Service는 더 이상 publishEvent()를 호출하지 않는다
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
            account.delete();          // 도메인 메서드로 불변식 검증 후 deletedAt 설정
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
// infrastructure/persistence/AccountJpaRepository.java — Spring Data가 구현을 자동 생성
public interface AccountJpaRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountIdAndOwnerIdAndDeletedAtIsNull(String accountId, String ownerId);
    Optional<Account> findByAccountIdAndDeletedAtIsNull(String accountId);
}
```

### Outbox — Aggregate 저장과 원자적으로 묶이는 이벤트 저장소

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
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
    }

    public void markProcessed() { this.processed = true; }
}
```

```java
// infrastructure/outbox/OutboxRelay.java — @Scheduled 폴링 후 메시지 큐 발행
package com.example.accountservice.account.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxEventJpaRepository outboxJpaRepository;

    @Scheduled(fixedDelay = 1000)   // 이전 폴링 종료 후 1초 뒤 재실행
    public void relay() {
        var pending = outboxJpaRepository.findTop100ByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                // 메시지 큐(SQS 등)로 발행 — 상세는 domain-events.md 3단계 참고
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error("Outbox relay 실패: eventId={}", event.getEventId(), e);
                // processed가 갱신되지 않으므로 다음 폴링에서 재시도된다
            }
        }
    }
}
```

`@EnableScheduling`이 `AccountServiceApplication`에 선언되어 있어야 이 `@Scheduled` 메서드가 동작한다([scheduling.md](architecture/scheduling.md) 참고).

---

## Interfaces 레이어

### Controller — 인증된 사용자 정보는 `Authentication`에서 추출

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

`examples/`의 현재 코드는 `@RequestHeader("X-User-Id")`로 인증되지 않은 값을 그대로 신뢰한다(알려진 gap, [authentication.md](architecture/authentication.md) 참고) — 이 템플릿은 Spring Security `SecurityFilterChain`이 이미 검증한 `Authentication`에서 `userId`(subject)를 꺼내는 목표 상태를 보여준다. `AccountException`에 대한 `@ExceptionHandler`는 도메인이 하나뿐일 때는 Controller 내부에 둘 수도 있지만, 아래처럼 전역 `@RestControllerAdvice`로 옮기면 두 번째 도메인이 추가되어도 중복 정의가 필요 없다.

### 전역 예외 처리 — `@RestControllerAdvice`, 4필드 응답

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
        log.warn("계좌 요청 실패: code={}, message={}", e.code(), e.getMessage());
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

### 에러 응답 — 4필드

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

`examples/`의 현재 `ErrorResponse`는 `code`/`message` 2필드뿐이다(알려진 gap, [error-handling.md](architecture/error-handling.md) 참고) — 이 템플릿은 `statusCode`/`error`까지 포함한 4필드 형식을 따른다.

### Interface DTO — Application 객체를 그대로 옮겨 담는 thin wrapper

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

Controller 메서드 한 줄이 매핑 지점이다: `new CreateAccountCommand(authentication.getName(), request.email(), request.currency())`. 별도 매핑 라이브러리(MapStruct 등)는 필드 수가 많아지기 전까지 불필요하다([layer-architecture.md](architecture/layer-architecture.md) "Interface DTO" 절 참고).

---

## 예외 / ErrorCode

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

## 설정 / 부트스트랩

```java
// AccountServiceApplication.java
package com.example.accountservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class})
@EnableScheduling   // OutboxRelay 등 @Scheduled 활성화 — scheduling.md
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

```java
// config/JwtProperties.java — 기동 시 fail-fast 검증, config.md 참고
package com.example.accountservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(@NotBlank String secret, long expirationSeconds) {}
```

```java
// common/config/SecurityConfig.java — authentication.md 참고
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

각 설정의 상세 근거(왜 `@ConfigurationProperties`+`@Validated`인지, 왜 `authorizeHttpRequests`를 전역에 적용하는지 등)는 [config.md](architecture/config.md), [authentication.md](architecture/authentication.md)를 참고한다 — 이 절은 조립된 최종 형태만 보여준다.
