package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionJpaRepository : JpaRepository<TransactionJpaEntity, Long> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: String, pageable: Pageable): List<TransactionJpaEntity>
    fun countByAccountId(accountId: String): Long
}
