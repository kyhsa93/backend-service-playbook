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
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountId;

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

    protected Account() {}

    public static Account create(String ownerId, String email, String currency) {
        Account account = new Account();
        account.accountId = IdGenerator.generate();
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
        if (amount <= 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_AMOUNT, "금액은 0보다 커야 합니다.");
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

    public void suspend() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT, "활성 상태의 계좌만 정지할 수 있습니다.");
        }
        this.status = AccountStatus.SUSPENDED;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(new AccountSuspendedEvent(this.accountId, this.email, this.updatedAt));
    }

    public void reactivate() {
        if (this.status != AccountStatus.SUSPENDED) {
            throw new AccountException(AccountException.ErrorCode.REACTIVATE_REQUIRES_SUSPENDED_ACCOUNT, "정지 상태의 계좌만 재개할 수 있습니다.");
        }
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(new AccountReactivatedEvent(this.accountId, this.email, this.updatedAt));
    }

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
