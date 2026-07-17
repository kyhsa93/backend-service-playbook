package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Account;

/**
 * Account(순수 도메인) ↔ AccountJpaEntity(JPA 매핑) 변환 전담 클래스. AccountRepositoryImpl 내부에서만 사용된다 —
 * Domain/Application 레이어는 이 클래스를 알지 못한다.
 */
final class AccountMapper {

    private AccountMapper() {}

    static Account toDomain(AccountJpaEntity entity) {
        return Account.reconstitute(
                entity.getAccountId(),
                entity.getOwnerId(),
                entity.getEmail(),
                entity.getBalance().toDomain(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }

    /** 신규 Account를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
    static AccountJpaEntity toNewEntity(Account account) {
        return new AccountJpaEntity(
                null,
                account.getAccountId(),
                account.getOwnerId(),
                account.getEmail(),
                MoneyEmbeddable.fromDomain(account.getBalance()),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                account.getDeletedAt());
    }

    /** 기존 엔티티(PK 보존)에 도메인 Account의 최신 상태를 반영한다 — update 대상. */
    static AccountJpaEntity updateEntity(AccountJpaEntity entity, Account account) {
        entity.applyMutableState(
                account.getEmail(),
                MoneyEmbeddable.fromDomain(account.getBalance()),
                account.getStatus(),
                account.getUpdatedAt(),
                account.getDeletedAt());
        return entity;
    }
}
