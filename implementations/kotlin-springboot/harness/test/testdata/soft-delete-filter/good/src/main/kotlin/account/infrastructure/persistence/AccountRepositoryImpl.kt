package com.example.accountservice.account.infrastructure.persistence

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class AccountRepositoryImpl(
    private val em: EntityManager,
) {
    fun findAccounts(): List<AccountJpaEntity> {
        val jpql = "SELECT a FROM AccountJpaEntity a WHERE a.deletedAt IS NULL"
        return em.createQuery(jpql, AccountJpaEntity::class.java).resultList
    }
}
