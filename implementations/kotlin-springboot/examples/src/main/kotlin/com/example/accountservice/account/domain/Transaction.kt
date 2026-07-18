package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Account Aggregate의 하위 Entity — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체.
 * 영속성 매핑은 infrastructure/persistence/TransactionJpaEntity + TransactionMapper가 전담한다.
 */
class Transaction private constructor() {
    var transactionId: String = ""
        private set

    var accountId: String = ""
        private set

    var type: TransactionType = TransactionType.DEPOSIT
        private set

    var amount: Money = Money(0, "")
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    // 외부 BC(Payment)의 Integration Event 반응으로 발생한 거래를 다른 BC의 Aggregate ID
    // (paymentId/refundId)로 상관관계 지을 수 있게 하는 선택 필드다. 사용자가 직접 요청한 입금/출금
    // (DepositService/WithdrawService)에는 없다(null) — WithdrawByPaymentService/
    // DepositByPaymentService에서만 채워지며, at-least-once 재수신 시 이 값 + type으로 중복 처리를
    // 막는 Level 2 Ledger 키로 쓰인다(domain-events.md "이벤트 핸들러 멱등성" 참고).
    var referenceId: String? = null
        private set

    companion object {
        fun create(
            accountId: String,
            type: TransactionType,
            amount: Money,
            referenceId: String? = null,
        ): Transaction =
            Transaction().apply {
                this.transactionId = generateId()
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.referenceId = referenceId
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Transaction을 복원할 때 사용한다.
         */
        fun reconstitute(
            transactionId: String,
            accountId: String,
            type: TransactionType,
            amount: Money,
            referenceId: String?,
            createdAt: LocalDateTime,
        ): Transaction =
            Transaction().apply {
                this.transactionId = transactionId
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.referenceId = referenceId
                this.createdAt = createdAt
            }
    }
}
