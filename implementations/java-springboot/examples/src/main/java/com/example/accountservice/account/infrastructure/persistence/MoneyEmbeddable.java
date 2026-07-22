package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Money;
import jakarta.persistence.Embeddable;

/**
 * The JPA-mapping counterpart of domain.Money. domain.Money is a pure Value Object that depends on
 * no framework, and this class handles the @Embedded column mapping entirely — it is used only in
 * AccountJpaEntity/TransactionJpaEntity.
 */
@Embeddable
public record MoneyEmbeddable(long amount, String currency) {

    public static MoneyEmbeddable fromDomain(Money money) {
        return new MoneyEmbeddable(money.amount(), money.currency());
    }

    public Money toDomain() {
        return new Money(amount, currency);
    }
}
