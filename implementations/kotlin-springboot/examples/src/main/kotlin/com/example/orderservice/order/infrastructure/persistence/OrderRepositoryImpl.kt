package com.example.orderservice.order.infrastructure.persistence

import com.example.orderservice.order.domain.Order
import com.example.orderservice.order.domain.OrderFindQuery
import com.example.orderservice.order.domain.OrderRepository
import com.example.orderservice.order.domain.OrderStatus
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class OrderRepositoryImpl(
    private val jpaRepository: OrderJpaRepository,
    private val em: EntityManager,
) : OrderRepository {

    override fun findById(orderId: String): Order? =
        jpaRepository.findByOrderIdAndDeletedAtIsNull(orderId)

    override fun findAll(query: OrderFindQuery): List<Order> {
        val jpql = buildJpql(query, count = false)
        return em.createQuery(jpql, Order::class.java)
            .setFirstResult(query.page * query.take)
            .setMaxResults(query.take)
            .apply { applyParams(this, query) }
            .resultList
    }

    override fun countAll(query: OrderFindQuery): Long {
        val jpql = buildJpql(query, count = true)
        return em.createQuery(jpql, Long::class.java)
            .apply { applyParams(this, query) }
            .singleResult
    }

    override fun save(order: Order) {
        jpaRepository.save(order)
    }

    override fun delete(orderId: String) {
        em.createQuery("UPDATE Order o SET o.deletedAt = :now WHERE o.orderId = :id")
            .setParameter("now", LocalDateTime.now())
            .setParameter("id", orderId)
            .executeUpdate()
    }

    private fun buildJpql(query: OrderFindQuery, count: Boolean): String {
        val select = if (count) "SELECT COUNT(o)" else "SELECT o"
        val sb = StringBuilder("$select FROM Order o WHERE o.deletedAt IS NULL")
        if (!query.userId.isNullOrBlank()) sb.append(" AND o.userId = :userId")
        if (!query.status.isNullOrEmpty()) sb.append(" AND o.status IN :status")
        if (!count) sb.append(" ORDER BY o.orderId DESC")
        return sb.toString()
    }

    private fun applyParams(q: jakarta.persistence.Query, query: OrderFindQuery) {
        if (!query.userId.isNullOrBlank()) q.setParameter("userId", query.userId)
        if (!query.status.isNullOrEmpty()) q.setParameter("status", query.status.map { OrderStatus.valueOf(it) })
    }
}
