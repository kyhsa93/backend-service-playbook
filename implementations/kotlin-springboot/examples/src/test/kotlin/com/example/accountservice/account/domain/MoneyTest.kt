package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {
    @Test
    fun `throws an exception on creation when the amount is negative`() {
        assertThrows<InvalidMoneyAmountException> { Money(-1, "KRW") }
    }

    @Test
    fun `adding the same currency returns a new Money with the amounts added`() {
        val result = Money(1000, "KRW").add(Money(500, "KRW"))

        assertThat(result).isEqualTo(Money(1500, "KRW"))
    }

    @Test
    fun `adding a different currency throws an exception`() {
        assertThrows<CurrencyMismatchException> { Money(1000, "KRW").add(Money(500, "USD")) }
    }

    @Test
    fun `subtracting the same currency returns a new Money with the amounts subtracted`() {
        val result = Money(1000, "KRW").subtract(Money(300, "KRW"))

        assertThat(result).isEqualTo(Money(700, "KRW"))
    }

    @Test
    fun `subtracting a different currency throws an exception`() {
        assertThrows<CurrencyMismatchException> { Money(1000, "KRW").subtract(Money(300, "USD")) }
    }

    @Test
    fun `isLessThan returns true when the amount is smaller`() {
        assertThat(Money(100, "KRW").isLessThan(Money(200, "KRW"))).isTrue()
        assertThat(Money(200, "KRW").isLessThan(Money(200, "KRW"))).isFalse()
    }

    @Test
    fun `isZero returns true when the amount is 0`() {
        assertThat(Money(0, "KRW").isZero()).isTrue()
        assertThat(Money(1, "KRW").isZero()).isFalse()
    }

    @Test
    fun `Money instances with the same amount and currency are equal`() {
        assertThat(Money(1000, "KRW")).isEqualTo(Money(1000, "KRW"))
    }
}
