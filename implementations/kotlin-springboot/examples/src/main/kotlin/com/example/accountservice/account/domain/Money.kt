package com.example.accountservice.account.domain

import jakarta.persistence.Embeddable

@Embeddable
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
