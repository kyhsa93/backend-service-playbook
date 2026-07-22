package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.Account;

/**
 * The class dedicated to converting between Account (pure domain) and AccountJpaEntity (JPA
 * mapping). It is used only inside AccountRepositoryImpl — the Domain/Application layers have no
 * knowledge of this class.
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
                entity.getDeletedAt(),
                entity.getLastInterestPaidAt());
    }

    /** Creates a new entity (no PK, to be inserted) for a new Account. */
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
                account.getDeletedAt(),
                account.getLastInterestPaidAt());
    }

    /**
     * Applies the domain Account's latest state onto the existing entity (PK preserved) — to be
     * updated.
     */
    static AccountJpaEntity updateEntity(AccountJpaEntity entity, Account account) {
        entity.applyMutableState(
                account.getEmail(),
                MoneyEmbeddable.fromDomain(account.getBalance()),
                account.getStatus(),
                account.getUpdatedAt(),
                account.getDeletedAt(),
                account.getLastInterestPaidAt());
        return entity;
    }
}
