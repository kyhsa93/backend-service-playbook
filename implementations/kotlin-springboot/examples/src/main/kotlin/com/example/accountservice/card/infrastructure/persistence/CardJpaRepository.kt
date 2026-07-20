package com.example.accountservice.card.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface CardJpaRepository : JpaRepository<CardJpaEntity, Long> {
    fun findByCardId(cardId: String): CardJpaEntity?
}
