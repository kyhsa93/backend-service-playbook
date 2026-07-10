package com.example.accountservice.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {

    @Test
    fun `금액이 음수면 생성 시 예외를 던진다`() {
        assertThrows<InvalidMoneyAmountException> { Money(-1, "KRW") }
    }

    @Test
    fun `같은 통화끼리 더하면 금액이 더해진 새 Money를 반환한다`() {
        val result = Money(1000, "KRW").add(Money(500, "KRW"))

        assertThat(result).isEqualTo(Money(1500, "KRW"))
    }

    @Test
    fun `다른 통화를 더하면 예외를 던진다`() {
        assertThrows<CurrencyMismatchException> { Money(1000, "KRW").add(Money(500, "USD")) }
    }

    @Test
    fun `같은 통화끼리 빼면 금액이 빠진 새 Money를 반환한다`() {
        val result = Money(1000, "KRW").subtract(Money(300, "KRW"))

        assertThat(result).isEqualTo(Money(700, "KRW"))
    }

    @Test
    fun `다른 통화를 빼면 예외를 던진다`() {
        assertThrows<CurrencyMismatchException> { Money(1000, "KRW").subtract(Money(300, "USD")) }
    }

    @Test
    fun `isLessThan 금액이 더 작으면 true를 반환한다`() {
        assertThat(Money(100, "KRW").isLessThan(Money(200, "KRW"))).isTrue()
        assertThat(Money(200, "KRW").isLessThan(Money(200, "KRW"))).isFalse()
    }

    @Test
    fun `isZero 금액이 0이면 true를 반환한다`() {
        assertThat(Money(0, "KRW").isZero()).isTrue()
        assertThat(Money(1, "KRW").isZero()).isFalse()
    }

    @Test
    fun `같은 금액과 통화를 가진 Money는 동등하다`() {
        assertThat(Money(1000, "KRW")).isEqualTo(Money(1000, "KRW"))
    }
}
