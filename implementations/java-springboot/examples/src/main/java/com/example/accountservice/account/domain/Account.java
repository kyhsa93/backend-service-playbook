package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    // 오늘 이미 이자를 지급받았는지 판단하는 Level 1(본질적 멱등) 필드 — payInterest() 참고.
    private LocalDate lastInterestPaidAt;

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
     * {@code referenceId}는 Payment BC의 Integration Event 반응(DepositByPaymentService)에서만 채워진다 — 사용자가
     * 직접 요청한 입금은 {@link #deposit(long)}(referenceId 없음)을 쓴다. Level 2 Ledger 멱등성 판단은 이 값 + type 조합으로
     * 이루어진다({@link AccountRepository#hasTransactionWithReference} 참고).
     */
    public Transaction deposit(long amount, String referenceId) {
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
     * {@code referenceId}는 Payment BC의 Integration Event 반응(WithdrawByPaymentService)에서만 채워진다 —
     * 사용자가 직접 요청한 출금은 {@link #withdraw(long)}(referenceId 없음)을 쓴다.
     */
    public Transaction withdraw(long amount, String referenceId) {
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

    // 고정 일 이자율 0.01%(=1/10000) — 정수 나눗셈으로 floor(balance * 0.0001)과 동일한 결과를 부동소수점
    // 오차 없이 계산한다. 향후 계좌 등급별로 이율이 달라질 필요가 생기면 외부 설정으로 옮기는 것을 검토한다.
    private static final long DAILY_INTEREST_RATE_DENOMINATOR = 10_000;

    /**
     * 활성 계좌에 일 단위 고정 이자(잔액 × 0.01%, 원 미만 절사)를 지급한다. 매일 1회 배치가 호출하는 시스템 주도 동작이라 사용자 Command 표면 없이
     * Aggregate 메서드로 직접 모델링한다 — 정기 이자 지급은 "요청"이 아니라 계좌 스스로의 정기 행동이다.
     *
     * <p>{@code lastInterestPaidAt}로 "오늘 이미 이자를 지급했는지"를 판단하는 Level 1(본질적 멱등) 설계다(domain-events.md
     * 멱등성 3단계, scheduling.md 참고) — 같은 날 배치가 at-least-once로 재실행돼도 이 필드가 오늘 날짜 이상이면 그냥 아무 일도 하지 않는다.
     * 이자가 0으로 계산되는 계좌(잔액이 너무 작음)는 Transaction을 만들지 않고 lastInterestPaidAt도 갱신하지 않는다 — 재실행해도 항상 같은(0)
     * 결과이므로 별도 장치 없이도 여전히 멱등하다.
     *
     * @return 이자가 지급되었으면 그 거래, 이미 오늘 지급했거나 이자가 0이면 empty
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

    public LocalDate getLastInterestPaidAt() {
        return lastInterestPaidAt;
    }
}
