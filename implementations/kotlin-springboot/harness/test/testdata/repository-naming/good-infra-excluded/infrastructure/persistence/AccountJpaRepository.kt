package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

// An internal Spring Data JPA interface inside infrastructure/persistence/ is not targeted by this
// rule — derived query methods(findByOwnerId, countByOwnerId, etc.) are allowed as an implementation
// detail.
interface AccountJpaRepository : JpaRepository<AccountJpaEntity, Long> {
    fun findByOwnerId(ownerId: String): List<AccountJpaEntity>

    fun countByOwnerId(ownerId: String): Long
}
