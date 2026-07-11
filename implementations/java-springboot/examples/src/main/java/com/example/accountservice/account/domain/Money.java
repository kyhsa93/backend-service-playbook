package com.example.accountservice.account.domain;

/**
 * Value Object — 순수 도메인 객체. 어떤 프레임워크/ORM에도 의존하지 않는다.
 * 영속성 매핑은 infrastructure/persistence/MoneyEmbeddable이 전담한다.
 */
public record Money(long amount, String currency) {

    public Money {
        if (amount < 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "금액은 0 이상이어야 합니다.");
        }
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount - other.amount, this.currency);
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount < other.amount;
    }

    public boolean isZero() {
        return this.amount == 0;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new AccountException(AccountException.ErrorCode.CURRENCY_MISMATCH, "통화가 일치하지 않습니다.");
        }
    }
}
