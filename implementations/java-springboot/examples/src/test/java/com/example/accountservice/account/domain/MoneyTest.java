package com.example.accountservice.account.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void 금액이_음수면_생성_시_예외를_던진다() {
        assertThatThrownBy(() -> new Money(-1, "KRW"))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.INVALID_MONEY_AMOUNT);
    }

    @Test
    void 같은_통화끼리_더하면_금액이_더해진_새_Money를_반환한다() {
        Money result = new Money(1000, "KRW").add(new Money(500, "KRW"));

        assertThat(result).isEqualTo(new Money(1500, "KRW"));
    }

    @Test
    void 다른_통화를_더하면_예외를_던진다() {
        assertThatThrownBy(() -> new Money(1000, "KRW").add(new Money(500, "USD")))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void 같은_통화끼리_빼면_금액이_빠진_새_Money를_반환한다() {
        Money result = new Money(1000, "KRW").subtract(new Money(300, "KRW"));

        assertThat(result).isEqualTo(new Money(700, "KRW"));
    }

    @Test
    void 다른_통화를_빼면_예외를_던진다() {
        assertThatThrownBy(() -> new Money(1000, "KRW").subtract(new Money(300, "USD")))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).code())
                .isEqualTo(AccountException.ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void isLessThan_금액이_더_작으면_true를_반환한다() {
        assertThat(new Money(100, "KRW").isLessThan(new Money(200, "KRW"))).isTrue();
        assertThat(new Money(200, "KRW").isLessThan(new Money(200, "KRW"))).isFalse();
    }

    @Test
    void isZero_금액이_0이면_true를_반환한다() {
        assertThat(new Money(0, "KRW").isZero()).isTrue();
        assertThat(new Money(1, "KRW").isZero()).isFalse();
    }

    @Test
    void 같은_금액과_통화를_가진_Money는_동등하다() {
        assertThat(new Money(1000, "KRW")).isEqualTo(new Money(1000, "KRW"));
    }
}
