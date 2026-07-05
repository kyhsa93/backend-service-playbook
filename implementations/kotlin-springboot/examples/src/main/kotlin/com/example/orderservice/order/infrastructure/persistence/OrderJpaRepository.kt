package com.example.orderservice.order.infrastructure.persistence

import com.example.orderservice.order.domain.Order
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<Order, Long> {
    fun findByOrderIdAndDeletedAtIsNull(orderId: String): Order?
}
