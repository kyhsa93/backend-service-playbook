package com.example.accountservice.auth.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface CredentialJpaRepository : JpaRepository<CredentialJpaEntity, Long> {
    fun findByUserId(userId: String): CredentialJpaEntity?
}
