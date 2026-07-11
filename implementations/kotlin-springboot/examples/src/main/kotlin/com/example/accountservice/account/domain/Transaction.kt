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

    companion object {
        fun create(accountId: String, type: TransactionType, amount: Money): Transaction =
            Transaction().apply {
                this.transactionId = generateId()
                this.accountId = accountId
                this.type = type
                this.amount = amount
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
            createdAt: LocalDateTime,
        ): Transaction =
            Transaction().apply {
                this.transactionId = transactionId
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.createdAt = createdAt
            }
    }
}
