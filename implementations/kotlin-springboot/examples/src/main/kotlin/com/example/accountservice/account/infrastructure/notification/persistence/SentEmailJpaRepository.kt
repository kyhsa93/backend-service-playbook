package com.example.accountservice.account.infrastructure.notification.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SentEmailJpaRepository : JpaRepository<SentEmail, Long> {
    fun findByAccountId(accountId: String): List<SentEmail>

    fun findBySesMessageId(sesMessageId: String): SentEmail?

    /** Level 2(Ledger) 멱등성 체크 — 이 Outbox 이벤트가 이미 이메일 발송으로 이어졌는지 확인한다. */
    fun existsBySourceEventId(sourceEventId: String): Boolean
}
