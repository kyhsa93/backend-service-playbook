package com.example.accountservice.card.infrastructure.persistence

import com.example.accountservice.card.domain.CardStatus
import org.springframework.data.jpa.repository.JpaRepository

interface CardJpaRepository : JpaRepository<CardJpaEntity, Long> {
    fun findByCardId(cardId: String): CardJpaEntity?
    fun findByCardIdAndOwnerId(cardId: String, ownerId: String): CardJpaEntity?
    fun findByAccountIdAndStatusInOrderByCardIdDesc(accountId: String, statuses: List<CardStatus>): List<CardJpaEntity>
}
