package com.example.accountservice.account.domain

/**
 * 금액 Value Object — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin `data class`.
 * JPA 임베더블 컬럼 매핑은 infrastructure/persistence/MoneyEmbeddable이 전담한다.
 */
data class Money(val amount: Long, val currency: String) {

    init {
        if (amount < 0) throw InvalidMoneyAmountException()
    }

    fun add(other: Money): Money {
        assertSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    fun subtract(other: Money): Money {
        assertSameCurrency(other)
        return Money(amount - other.amount, currency)
    }

    fun isLessThan(other: Money): Boolean {
        assertSameCurrency(other)
        return amount < other.amount
    }

    fun isZero(): Boolean = amount == 0L

    private fun assertSameCurrency(other: Money) {
        if (currency != other.currency) throw CurrencyMismatchException()
    }
}
