package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Account
import org.springframework.data.jpa.repository.JpaRepository

interface AccountJpaRepository : JpaRepository<Account, Long> {
    fun findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId: String, ownerId: String): Account?
    fun findByAccountIdAndDeletedAtIsNull(accountId: String): Account?
}
