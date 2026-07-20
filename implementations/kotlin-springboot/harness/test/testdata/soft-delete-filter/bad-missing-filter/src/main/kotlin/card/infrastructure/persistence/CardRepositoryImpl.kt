package com.example.accountservice.card.infrastructure.persistence

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class CardRepositoryImpl(
    private val em: EntityManager,
) {
    fun findCards(): List<CardJpaEntity> {
        val jpql = "SELECT c FROM CardJpaEntity c WHERE 1 = 1"
        return em.createQuery(jpql, CardJpaEntity::class.java).resultList
    }
}
