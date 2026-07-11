package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Money;
import jakarta.persistence.Embeddable;

/**
 * domain.Money의 JPA 매핑 전용 대응물. domain.Money는 어떤 프레임워크에도 의존하지 않는 순수 Value Object이고,
 * 이 클래스가 @Embedded 컬럼 매핑을 전담한다 — AccountJpaEntity/TransactionJpaEntity에서만 사용된다.
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
