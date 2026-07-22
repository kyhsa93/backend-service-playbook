package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Money
import jakarta.persistence.Embeddable

/**
 * The JPA-mapping counterpart of domain.Money. domain.Money is a pure Value Object with no dependency on
 * any framework, and this class handles the `@Embedded` column mapping exclusively — used only in
 * AccountJpaEntity/TransactionJpaEntity.
 *
 * Unlike domain.Money, this does not validate the amount (>= 0) — it merely reconstitutes an already
 * committed value as-is; invariant validation is the responsibility of the domain.Money that toDomain()
 * constructs.
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
