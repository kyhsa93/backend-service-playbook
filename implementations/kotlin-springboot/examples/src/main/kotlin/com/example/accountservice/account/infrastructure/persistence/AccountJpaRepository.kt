package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface AccountJpaRepository : JpaRepository<AccountJpaEntity, Long> {
    fun findByAccountId(accountId: String): AccountJpaEntity?

    fun findByAccountIdAndDeletedAtIsNull(accountId: String): AccountJpaEntity?
}
