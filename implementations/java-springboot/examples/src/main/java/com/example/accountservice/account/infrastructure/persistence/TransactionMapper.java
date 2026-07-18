package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Transaction;

/**
 * Transaction(순수 도메인) ↔ TransactionJpaEntity(JPA 매핑) 변환 전담 클래스. AccountRepositoryImpl 내부에서만 사용된다.
 * Transaction은 생성 후 불변이므로 insert 전용 변환만 필요하다.
 */
final class TransactionMapper {

    private TransactionMapper() {}

    static Transaction toDomain(TransactionJpaEntity entity) {
        return Transaction.reconstitute(
                entity.getTransactionId(),
                entity.getAccountId(),
                entity.getType(),
                entity.getAmount().toDomain(),
                entity.getReferenceId(),
                entity.getCreatedAt());
    }

    static TransactionJpaEntity toNewEntity(Transaction transaction) {
        return new TransactionJpaEntity(
                null,
                transaction.getTransactionId(),
                transaction.getAccountId(),
                transaction.getType(),
                MoneyEmbeddable.fromDomain(transaction.getAmount()),
                transaction.getReferenceId(),
                transaction.getCreatedAt());
    }
}
