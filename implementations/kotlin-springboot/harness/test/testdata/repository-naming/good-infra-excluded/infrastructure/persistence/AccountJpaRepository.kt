package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

// infrastructure/persistence/ 안의 내부 Spring Data JPA 인터페이스는 이 규칙의 대상이 아니다 —
// derived query 메서드(findByOwnerId, countByOwnerId 등)는 구현 세부사항으로 허용된다.
interface AccountJpaRepository : JpaRepository<AccountJpaEntity, Long> {
    fun findByOwnerId(ownerId: String): List<AccountJpaEntity>

    fun countByOwnerId(ownerId: String): Long
}
