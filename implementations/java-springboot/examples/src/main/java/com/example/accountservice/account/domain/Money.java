package com.example.accountservice.account.domain;

/**
 * Value Object — a pure domain object. It does not depend on any framework/ORM. Persistence mapping
 * is handled entirely by infrastructure/persistence/MoneyEmbeddable.
 */
public record Money(long amount, String currency) {

    public Money {
        if (amount < 0) {
            throw new AccountException(
                    AccountException.ErrorCode.INVALID_MONEY_AMOUNT,
                    "Amount must be 0 or greater.");
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
            throw new AccountException(
                    AccountException.ErrorCode.CURRENCY_MISMATCH, "Currency mismatch.");
        }
    }
}
