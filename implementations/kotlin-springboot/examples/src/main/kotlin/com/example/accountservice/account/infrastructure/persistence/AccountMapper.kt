package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Account

/**
 * An object dedicated to converting between Account (pure domain) and AccountJpaEntity (JPA mapping).
 * Used only inside AccountRepositoryImpl — the Domain/Application layers have no awareness of this
 * object.
 */
internal object AccountMapper {
    fun toDomain(entity: AccountJpaEntity): Account =
        Account.reconstitute(
            accountId = entity.accountId,
            ownerId = entity.ownerId,
            email = entity.email,
            balance = entity.balance.toDomain(),
            status = entity.status,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            deletedAt = entity.deletedAt,
            lastInterestPaidAt = entity.lastInterestPaidAt,
        )

    /** Creates a new entity (no PK, for insertion) for a new Account. */
    fun toNewEntity(account: Account): AccountJpaEntity =
        AccountJpaEntity(
            id = null,
            accountId = account.accountId,
            ownerId = account.ownerId,
            email = account.email,
            balance = MoneyEmbeddable.fromDomain(account.balance),
            status = account.status,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
            deletedAt = account.deletedAt,
            lastInterestPaidAt = account.lastInterestPaidAt,
        )

    /** Reflects the domain Account's latest state onto an existing entity (PK preserved) — for updates. */
    fun updateEntity(
        entity: AccountJpaEntity,
        account: Account,
    ): AccountJpaEntity {
        entity.email = account.email
        entity.balance = MoneyEmbeddable.fromDomain(account.balance)
        entity.status = account.status
        entity.updatedAt = account.updatedAt
        entity.deletedAt = account.deletedAt
        entity.lastInterestPaidAt = account.lastInterestPaidAt
        return entity
    }
}
