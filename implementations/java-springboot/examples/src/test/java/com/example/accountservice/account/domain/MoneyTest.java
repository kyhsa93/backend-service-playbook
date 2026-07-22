package com.example.accountservice.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void throws_exception_on_construction_when_amount_is_negative() {
        assertThatThrownBy(() -> new Money(-1, "KRW"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INVALID_MONEY_AMOUNT);
    }

    @Test
    void adding_the_same_currency_returns_a_new_Money_with_the_summed_amount() {
        Money result = new Money(1000, "KRW").add(new Money(500, "KRW"));

        assertThat(result).isEqualTo(new Money(1500, "KRW"));
    }

    @Test
    void throws_exception_when_adding_a_different_currency() {
        assertThatThrownBy(() -> new Money(1000, "KRW").add(new Money(500, "USD")))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void subtracting_the_same_currency_returns_a_new_Money_with_the_reduced_amount() {
        Money result = new Money(1000, "KRW").subtract(new Money(300, "KRW"));

        assertThat(result).isEqualTo(new Money(700, "KRW"));
    }

    @Test
    void throws_exception_when_subtracting_a_different_currency() {
        assertThatThrownBy(() -> new Money(1000, "KRW").subtract(new Money(300, "USD")))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void isLessThan_returns_true_when_amount_is_smaller() {
        assertThat(new Money(100, "KRW").isLessThan(new Money(200, "KRW"))).isTrue();
        assertThat(new Money(200, "KRW").isLessThan(new Money(200, "KRW"))).isFalse();
    }

    @Test
    void isZero_returns_true_when_amount_is_zero() {
        assertThat(new Money(0, "KRW").isZero()).isTrue();
        assertThat(new Money(1, "KRW").isZero()).isFalse();
    }

    @Test
    void Money_with_the_same_amount_and_currency_is_equal() {
        assertThat(new Money(1000, "KRW")).isEqualTo(new Money(1000, "KRW"));
    }
}
