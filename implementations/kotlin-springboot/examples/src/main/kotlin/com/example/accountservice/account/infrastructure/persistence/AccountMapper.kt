package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Account

/**
 * Account(순수 도메인) ↔ AccountJpaEntity(JPA 매핑) 변환 전담 오브젝트.
 * AccountRepositoryImpl 내부에서만 사용된다 — Domain/Application 레이어는 이 오브젝트를 알지 못한다.
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
        )

    /** 신규 Account를 위한 새 엔티티(PK 없음, insert 대상)를 생성한다. */
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
        )

    /** 기존 엔티티(PK 보존)에 도메인 Account의 최신 상태를 반영한다 — update 대상. */
    fun updateEntity(entity: AccountJpaEntity, account: Account): AccountJpaEntity {
        entity.email = account.email
        entity.balance = MoneyEmbeddable.fromDomain(account.balance)
        entity.status = account.status
        entity.updatedAt = account.updatedAt
        entity.deletedAt = account.deletedAt
        return entity
    }
}
