package com.example.accountservice.notification.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SentEmailJpaRepository : JpaRepository<SentEmail, Long> {
    fun findByAccountId(accountId: String): List<SentEmail>
    fun findBySesMessageId(sesMessageId: String): SentEmail?
}
