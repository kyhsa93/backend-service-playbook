package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Money
import jakarta.persistence.Embeddable

/**
 * domain.Money의 JPA 매핑 전용 대응물. domain.Money는 어떤 프레임워크에도 의존하지 않는 순수 Value Object이고,
 * 이 클래스가 `@Embedded` 컬럼 매핑을 전담한다 — AccountJpaEntity/TransactionJpaEntity에서만 사용된다.
 *
 * domain.Money와 달리 여기서는 금액 유효성(0 이상)을 검증하지 않는다 — 이미 커밋된 값을 그대로 재구성할 뿐이며,
 * 불변식 검증은 toDomain()이 생성하는 domain.Money의 책임이다.
 */
@Embeddable
data class MoneyEmbeddable(
    val amount: Long = 0,
    val currency: String = "",
) {
    fun toDomain(): Money = Money(amount, currency)

    companion object {
        fun fromDomain(money: Money): MoneyEmbeddable = MoneyEmbeddable(money.amount, money.currency)
    }
}
