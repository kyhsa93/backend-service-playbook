package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Account Aggregate Root — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체.
 * 영속성 매핑은 infrastructure/persistence/AccountJpaEntity + AccountMapper가 전담한다.
 */
class Account private constructor() {
    var accountId: String = ""
        private set

    var ownerId: String = ""
        private set

    var email: String = ""
        private set

    var balance: Money = Money(0, "")
        private set

    var status: AccountStatus = AccountStatus.ACTIVE
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    var updatedAt: LocalDateTime = LocalDateTime.now()
        private set

    var deletedAt: LocalDateTime? = null
        private set

    // 정기 이자 지급(PayInterestService)의 Level 1(본질적 멱등) 키다 — "오늘 이미 이자를 받았는가"를
    // Account 스스로 알고 있으므로, 같은 날 Task가 at-least-once로 두 번 실행돼도 두 번째 호출은
    // 아무 부작용 없이 스킵된다(별도 Ledger 테이블 불필요, domain-events.md 멱등성 1단계).
    var lastInterestPaidAt: LocalDate? = null
        private set

    private val domainEvents: MutableList<DomainEvent> = mutableListOf()

    private val pendingTransactions: MutableList<Transaction> = mutableListOf()

    companion object {
        // 일 이자율 0.01% — 정기 이자 지급(payInterest) 전용 상수. Application 레이어(Scheduler/
        // Command Service)가 값을 주입하지 않고 Aggregate가 스스로 갖는다 — "이자를 얼마나 주는가"는
        // Account의 불변식이 아니라 정책값이지만, 이 저장소 규모에서는 별도 정책 테이블/설정으로
        // 분리할 실익이 없어 도메인 상수로 둔다(YAGNI).
        private val DAILY_INTEREST_RATE = BigDecimal("0.0001")

        fun create(
            ownerId: String,
            currency: String,
            email: String,
        ): Account =
            Account().apply {
                this.accountId = generateId()
                this.ownerId = ownerId
                this.email = email
                this.balance = Money(0, currency)
                this.status = AccountStatus.ACTIVE
                this.createdAt = LocalDateTime.now()
                this.updatedAt = this.createdAt
                this.domainEvents += AccountCreatedEvent(this.accountId, ownerId, email, currency, this.createdAt)
            }

        /**
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Account를 복원할 때 사용한다.
         * create()와 달리 도메인 이벤트를 생성하지 않는다 — 이미 과거에 커밋된 상태를 그대로 재구성하는 것뿐이다.
         */
        fun reconstitute(
            accountId: String,
            ownerId: String,
            email: String,
            balance: Money,
            status: AccountStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
            deletedAt: LocalDateTime?,
            lastInterestPaidAt: LocalDate? = null,
        ): Account =
            Account().apply {
                this.accountId = accountId
                this.ownerId = ownerId
                this.email = email
                this.balance = balance
                this.status = status
                this.createdAt = createdAt
                this.updatedAt = updatedAt
                this.deletedAt = deletedAt
                this.lastInterestPaidAt = lastInterestPaidAt
            }
    }

    fun deposit(
        amount: Long,
        referenceId: String? = null,
    ): Transaction {
        if (status != AccountStatus.ACTIVE) throw DepositRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        balance = balance.add(money)
        updatedAt = LocalDateTime.now()
        val transaction = Transaction.create(accountId, TransactionType.DEPOSIT, money, referenceId)
        pendingTransactions += transaction
        domainEvents += MoneyDepositedEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    fun withdraw(
        amount: Long,
        referenceId: String? = null,
    ): Transaction {
        if (status != AccountStatus.ACTIVE) throw WithdrawRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        if (balance.isLessThan(money)) throw InsufficientBalanceException()
        balance = balance.subtract(money)
        updatedAt = LocalDateTime.now()
        val transaction = Transaction.create(accountId, TransactionType.WITHDRAWAL, money, referenceId)
        pendingTransactions += transaction
        domainEvents += MoneyWithdrawnEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    /**
     * 정기 이자 지급(system-initiated, PayInterestTaskController → PayInterestService가 호출) —
     * 사용자 Command가 아니므로 deposit()과 달리 예외를 던지지 않고 "적용할 게 없으면" `null`을
     * 반환한다. PayInterestService는 수천 개 계좌를 한 Task 안에서 순회하므로, 계좌 하나가 이미
     * 처리됐거나(레이스로 인한 상태 변경 등) 이자가 0원이라는 이유로 배치 전체가 예외로 중단되면 안
     * 된다 — deposit()/withdraw()의 "잘못된 커맨드는 즉시 실패"와는 다른 요구사항이다.
     *
     * 멱등성은 [lastInterestPaidAt]이 [payDate]와 같은지 확인하는 것만으로 충분하다(Level 1, 본질적
     * 멱등) — 같은 날짜로 두 번 호출돼도(at-least-once 재전달) 두 번째 호출은 아무것도 바꾸지 않고
     * `null`을 반환한다. 호출자(PayInterestService)도 `AccountFindQuery.excludeInterestPaidDate`로
     * 이미 지급된 계좌를 조회 단계에서 걸러내지만, 이 메서드 자체도 방어적으로 다시 확인한다.
     */
    fun payInterest(payDate: LocalDate): Transaction? {
        if (status != AccountStatus.ACTIVE) return null
        if (lastInterestPaidAt == payDate) return null
        val interestAmount =
            BigDecimal(balance.amount)
                .multiply(DAILY_INTEREST_RATE)
                .setScale(0, RoundingMode.FLOOR)
                .toLong()
        if (interestAmount <= 0) return null

        val money = Money(interestAmount, balance.currency)
        balance = balance.add(money)
        updatedAt = LocalDateTime.now()
        lastInterestPaidAt = payDate
        val transaction = Transaction.create(accountId, TransactionType.INTEREST, money)
        pendingTransactions += transaction
        domainEvents += InterestPaidEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    fun suspend() {
        if (status != AccountStatus.ACTIVE) throw SuspendRequiresActiveAccountException()
        status = AccountStatus.SUSPENDED
        updatedAt = LocalDateTime.now()
        domainEvents += AccountSuspendedEvent(accountId, email, updatedAt)
    }

    fun reactivate() {
        if (status != AccountStatus.SUSPENDED) throw ReactivateRequiresSuspendedAccountException()
        status = AccountStatus.ACTIVE
        updatedAt = LocalDateTime.now()
        domainEvents += AccountReactivatedEvent(accountId, email, updatedAt)
    }

    fun close() {
        if (status == AccountStatus.CLOSED) throw AccountAlreadyClosedException()
        if (!balance.isZero()) throw AccountBalanceNotZeroException()
        status = AccountStatus.CLOSED
        updatedAt = LocalDateTime.now()
        domainEvents += AccountClosedEvent(accountId, email, updatedAt)
    }

    fun markDeleted() {
        if (status != AccountStatus.CLOSED) throw DeleteRequiresClosedAccountException()
        deletedAt = LocalDateTime.now()
        updatedAt = deletedAt!!
    }

    fun pullDomainEvents(): List<DomainEvent> = domainEvents.toList().also { domainEvents.clear() }

    fun pullPendingTransactions(): List<Transaction> = pendingTransactions.toList().also { pendingTransactions.clear() }
}
