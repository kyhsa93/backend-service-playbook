package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface AccountJpaRepository : JpaRepository<AccountJpaEntity, Long> {
    fun findByAccountIdAndOwnerIdAndDeletedAtIsNull(
        accountId: String,
        ownerId: String,
    ): AccountJpaEntity?

    fun findByAccountId(accountId: String): AccountJpaEntity?

    fun findByAccountIdAndDeletedAtIsNull(accountId: String): AccountJpaEntity?
}
