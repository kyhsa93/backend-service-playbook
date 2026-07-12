package com.example.accountservice.card.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Card Aggregate Root — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체.
 * 영속성 매핑은 infrastructure/persistence/CardJpaEntity + CardMapper가 전담한다
 * (account/domain/Account.kt와 동일한 domain/JPA 분리 구조, #169 참고).
 */
class Card private constructor() {

    var cardId: String = ""
        private set

    var accountId: String = ""
        private set

    var ownerId: String = ""
        private set

    var brand: String = ""
        private set

    var status: CardStatus = CardStatus.ACTIVE
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        /**
         * 새 카드를 발급한다 — 항상 ACTIVE 상태로 시작한다.
         *
         * 연결 계좌의 활성 여부는 Card Aggregate가 알 수 없다. 발급 가능 여부(계좌 존재·활성)는
         * Application 레이어가 AccountAdapter(ACL)로 동기 조회해 판단한 뒤 이 팩토리를 호출한다.
         */
        fun issue(accountId: String, ownerId: String, brand: String): Card =
            Card().apply {
                this.cardId = generateId()
                this.accountId = accountId
                this.ownerId = ownerId
                this.brand = brand
                this.status = CardStatus.ACTIVE
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Card를 복원할 때 사용한다.
         * issue()와 달리 새 식별자·시각을 만들지 않고 저장된 상태를 그대로 재구성한다.
         */
        fun reconstitute(
            cardId: String,
            accountId: String,
            ownerId: String,
            brand: String,
            status: CardStatus,
            createdAt: LocalDateTime,
        ): Card =
            Card().apply {
                this.cardId = cardId
                this.accountId = accountId
                this.ownerId = ownerId
                this.brand = brand
                this.status = status
                this.createdAt = createdAt
            }
    }

    fun suspend() {
        if (status == CardStatus.CANCELLED) throw CancelledCardCannotBeSuspendedException()
        if (status == CardStatus.SUSPENDED) throw CardAlreadySuspendedException()
        status = CardStatus.SUSPENDED
    }

    fun cancel() {
        if (status == CardStatus.CANCELLED) throw CardAlreadyCancelledException()
        status = CardStatus.CANCELLED
    }
}
