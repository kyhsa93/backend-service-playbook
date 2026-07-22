package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Account Aggregate Root — a pure domain object. It does not depend on any framework/ORM.
 * Persistence mapping is handled entirely by infrastructure/persistence/AccountJpaEntity +
 * AccountMapper.
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
    // Level 1 (intrinsic idempotency) field used to determine whether interest has already been
    // paid today — see payInterest().
    private LocalDate lastInterestPaidAt;

    private final List<Object> domainEvents = new ArrayList<>();
    private final List<Transaction> pendingTransactions = new ArrayList<>();

    private Account() {}

    /**
     * Used by the Repository implementation to restore an Account from persisted data (a JPA
     * entity, etc). Unlike create(), it does not generate domain events — it merely reconstructs a
     * state that was already committed in the past.
     */
    public static Account reconstitute(
            String accountId,
            String ownerId,
            String email,
            Money balance,
            AccountStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt,
            LocalDate lastInterestPaidAt) {
        Account account = new Account();
        account.accountId = accountId;
        account.ownerId = ownerId;
        account.email = email;
        account.balance = balance;
        account.status = status;
        account.createdAt = createdAt;
        account.updatedAt = updatedAt;
        account.deletedAt = deletedAt;
        account.lastInterestPaidAt = lastInterestPaidAt;
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
        return deposit(amount, null);
    }

    /**
     * {@code referenceId} is only populated from the Payment BC's Integration Event reaction
     * (DepositByPaymentService) — a user-initiated deposit uses {@link #deposit(long)} (no
     * referenceId). Level 2 Ledger idempotency is determined by the combination of this value and
     * the type (see {@link AccountRepository#hasTransactionWithReference}).
     */
    public Transaction deposit(long amount, String referenceId) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(
                    AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can receive deposits.");
        }
        if (amount <= 0) {
            throw new AccountException(
                    AccountException.ErrorCode.INVALID_AMOUNT, "Amount must be greater than 0.");
        }
        Money money = new Money(amount, this.balance.currency());
        this.balance = this.balance.add(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction =
                Transaction.create(this.accountId, TransactionType.DEPOSIT, money, referenceId);
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
        return withdraw(amount, null);
    }

    /**
     * {@code referenceId} is only populated from the Payment BC's Integration Event reaction
     * (WithdrawByPaymentService) — a user-initiated withdrawal uses {@link #withdraw(long)} (no
     * referenceId).
     */
    public Transaction withdraw(long amount, String referenceId) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(
                    AccountException.ErrorCode.WITHDRAW_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can make withdrawals.");
        }
        if (amount <= 0) {
            throw new AccountException(
                    AccountException.ErrorCode.INVALID_AMOUNT, "Amount must be greater than 0.");
        }
        Money money = new Money(amount, this.balance.currency());
        if (this.balance.isLessThan(money)) {
            throw new AccountException(
                    AccountException.ErrorCode.INSUFFICIENT_BALANCE, "Insufficient balance.");
        }
        this.balance = this.balance.subtract(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction =
                Transaction.create(this.accountId, TransactionType.WITHDRAWAL, money, referenceId);
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

    // Fixed daily interest rate of 0.01% (=1/10000) — computed with integer division so the result
    // is identical to floor(balance * 0.0001) with no floating-point error. If the rate ever needs
    // to vary by account tier, consider moving it to external configuration.
    private static final long DAILY_INTEREST_RATE_DENOMINATOR = 10_000;

    /**
     * Pays fixed daily interest (balance x 0.01%, truncated below the smallest currency unit) to an
     * active account. This is a system-driven action invoked once daily by a batch job, so it is
     * modeled directly as an Aggregate method with no user-facing Command surface — scheduled
     * interest payment is not a "request," it is the account's own periodic behavior.
     *
     * <p>{@code lastInterestPaidAt} implements a Level 1 (intrinsic idempotency) design for
     * deciding "has interest already been paid today" (see the 3 levels of idempotency in
     * domain-events.md, and scheduling.md) — if the batch job re-runs at-least-once on the same
     * day, this field being today's date or later means it simply does nothing. An account whose
     * computed interest is 0 (balance too small) does not create a Transaction and does not update
     * lastInterestPaidAt either — since re-running always produces the same (0) result, it remains
     * idempotent without any extra guard.
     *
     * @return the transaction if interest was paid, or empty if it was already paid today or the
     *     interest amount is 0
     */
    public Optional<Transaction> payInterest(LocalDate today) {
        if (this.status != AccountStatus.ACTIVE) {
            return Optional.empty();
        }
        if (this.lastInterestPaidAt != null && !this.lastInterestPaidAt.isBefore(today)) {
            return Optional.empty();
        }
        long interestAmount = this.balance.amount() / DAILY_INTEREST_RATE_DENOMINATOR;
        if (interestAmount <= 0) {
            return Optional.empty();
        }
        Money interest = new Money(interestAmount, this.balance.currency());
        this.balance = this.balance.add(interest);
        this.lastInterestPaidAt = today;
        this.updatedAt = LocalDateTime.now();
        Transaction transaction =
                Transaction.create(this.accountId, TransactionType.INTEREST, interest, null);
        this.pendingTransactions.add(transaction);
        return Optional.of(transaction);
    }

    public void suspend() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(
                    AccountException.ErrorCode.SUSPEND_REQUIRES_ACTIVE_ACCOUNT,
                    "Only an active account can be suspended.");
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
                    "Only a suspended account can be reactivated.");
        }
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(
                new AccountReactivatedEvent(this.accountId, this.email, this.updatedAt));
    }

    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_ALREADY_CLOSED,
                    "The account is already closed.");
        }
        if (!this.balance.isZero()) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_BALANCE_NOT_ZERO,
                    "An account with a non-zero balance cannot be closed.");
        }
        this.status = AccountStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
        this.domainEvents.add(new AccountClosedEvent(this.accountId, this.email, this.updatedAt));
    }

    /**
     * Soft-deletes the account record — records a timestamp in deletedAt. "Closing" (close, a
     * business state transition) and "deleting" (delete, data lifecycle management) are distinct
     * concepts. Only a closed (CLOSED) account can be deleted.
     */
    public void delete() {
        if (this.status != AccountStatus.CLOSED) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_NOT_CLOSABLE_FOR_DELETE,
                    "Only a closed account can be deleted.");
        }
        if (this.deletedAt != null) {
            throw new AccountException(
                    AccountException.ErrorCode.ACCOUNT_ALREADY_DELETED,
                    "The account is already deleted.");
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

    public LocalDate getLastInterestPaidAt() {
        return lastInterestPaidAt;
    }
}
