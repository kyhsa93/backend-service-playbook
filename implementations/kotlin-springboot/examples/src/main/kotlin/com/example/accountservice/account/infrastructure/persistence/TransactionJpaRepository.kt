package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.Transaction
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionJpaRepository : JpaRepository<Transaction, Long> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: String, pageable: Pageable): List<Transaction>
    fun countByAccountId(accountId: String): Long
}
