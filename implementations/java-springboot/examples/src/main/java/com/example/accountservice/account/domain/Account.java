package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Account Aggregate Root — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다. 영속성 매핑은
 * infrastructure/persistence/AccountJpaEntity + AccountMapper가 전담한다.
 */
public class Account {

    private String accountId;
    private String ownerId;
    private String email;
    private Money balance;
    private AccountStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    private final List<Object> domainEvents = new ArrayList<>();
    private final List<Transaction> pendingTransactions = new ArrayList<>();

    private Account() {}

    /**
     * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Account를 복원할 때 사용한다. create()와 달리 도메인 이벤트를 생성하지 않는다 — 이미
     * 과거에 커밋된 상태를 그대로 재구성하는 것뿐이다.
     */
    public static Account reconstitute(
            String accountId,
            String ownerId,
            String email,
            Money balance,
            AccountStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt) {
        Account account = new Account();
        account.accountId = accountId;
        account.ownerId = ownerId;
        account.email = email;
        account.balance = balance;
        account.status = status;
        account.createdAt = createdAt;
        account.updatedAt = updatedAt;
        account.deletedAt = deletedAt;
        return account;
    }

    public static Account create(String ownerId, String email, String currency) {
        Account account = new Account();
        account.accountId = IdGenerator.generate();
        account.ownerId = ownerId;
        account.email = email;
        account.balance = new Money(0, currency);
        account.status = AccountStatus.ACTIVE;
        account.createdAt = LocalDateTime.now();
        account.updatedAt = account.createdAt;
        account.domainEvents.add(
                new AccountCreatedEvent(
                        account.accountId, ownerId, email, currency, account.createdAt));
        return account;
    }

    public Transaction deposit(long amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(
                    AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌만 입금할 수 있습니다.");
        }
        if (amount <= 0) {
            throw new AccountException(
                    AccountException.ErrorCode.INVALID_AMOUNT, "금액은 0보다 커야 합니다.");
        }
        Money money = new Money(amount, this.balance.currency());
        this.balance = this.balance.add(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction =
                Transaction.create(this.accountId, TransactionType.DEPOSIT, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(
                new MoneyDepositedEvent(
                        this.accountId,
                        this.email,
                        transaction.getTransactionId(),
                        money,
                        this.balance,
                        transaction.getCreatedAt()));
        return transaction;
    }

    public Transaction withdraw(long amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(
                    AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌만 출금할 수 있습니다.");
        }
        if (amount <= 0) {
            throw new AccountException(
                    AccountException.ErrorCode.INVALID_AMOUNT, "금액은 0보다 커야 합니다.");
        }
        Money money = new Money(amount, this.balance.currency());
        if (this.balance.isLessThan(money)) {
            throw new AccountException(
                    AccountException.ErrorCode.INSUFFICIENT_BALANCE, "잔액이 부족합니다.");
        }
        this.balance = this.balance.subtract(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction =
                Transaction.create(this.accountId, TransactionType.WITHDRAWAL, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(
                new MoneyWithdrawnEvent(
                        this.accountId,
                        this.email,
                        transaction.getTransactionId(),
                        money,
                        this.balance,
                        transaction.getCreatedAt()));
        return transaction;
    }

    public void suspend() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(
                    AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT,
                    "활성 상태의 계좌만 정지할 수 있습니다.");
        }
        this.status = AccountStatus.SUSPENDED;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(
                new AccountSuspendedEvent(this.accountId, this.email, this.updatedAt));
    }

    public void reactivate() {
        if (this.status != AccountStatus.SUSPENDED) {
            throw new AccountException(
                    AccountException.ErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT,
                    "정지 상태의 계좌만 재개할 수 있습니다.");
        }
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(
                new AccountReactivatedEvent(this.accountId, this.email, this.updatedAt));
    }

    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_ALREADY_CLOSED, "이미 종료된 계좌입니다.");
        }
        if (!this.balance.isZero()) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO,
                    "잔액이 0이 아닌 계좌는 종료할 수 없습니다.");
        }
        this.status = AccountStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(new AccountClosedEvent(this.accountId, this.email, this.updatedAt));
    }

    /**
     * 계좌 레코드를 soft delete한다 — deletedAt에 타임스탬프를 기록한다. "종료"(close, 비즈니스 상태 전이)와 "삭제"(delete, 데이터
     * 생명주기 관리)는 서로 다른 개념이다. 종료된(CLOSED) 계좌만 삭제할 수 있다.
     */
    public void delete() {
        if (this.status != AccountStatus.CLOSED) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_NOT_CLOSABLE_FOR_DELETE,
                    "종료된 계좌만 삭제할 수 있습니다.");
        }
        if (this.deletedAt != null) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_ALREADY_DELETED, "이미 삭제된 계좌입니다.");
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

    public String getAccountId() {
        return accountId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getEmail() {
        return email;
    }

    public Money getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }
}
