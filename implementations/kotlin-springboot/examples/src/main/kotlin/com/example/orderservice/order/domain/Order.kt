package com.example.orderservice.order.domain

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "orders")
class Order protected constructor() {

    @Column(nullable = false, unique = true)
    var orderId: String = ""
        private set

    @Column(nullable = false)
    var userId: String = ""
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.PENDING
        private set

    @ElementCollection
    @CollectionTable(
        name = "order_items",
        joinColumns = [JoinColumn(name = "order_id", referencedColumnName = "order_id")]
    )
    var items: MutableList<OrderItem> = mutableListOf()
        private set

    @Column
    var deletedAt: LocalDateTime? = null
        private set

    @Transient
    private val domainEvents: MutableList<Any> = mutableListOf()

    companion object {
        fun create(userId: String, items: List<OrderItem>): Order {
            require(items.isNotEmpty()) { "주문 항목은 최소 1개 이상이어야 합니다." }
            return Order().apply {
                this.orderId = UUID.randomUUID().toString()
                this.userId = userId
                this.items = items.toMutableList()
                this.status = OrderStatus.PENDING
            }
        }
    }

    fun cancel(reason: String) {
        if (status == OrderStatus.CANCELLED) throw OrderAlreadyCancelledException()
        if (status == OrderStatus.PAID) throw OrderPaidNotCancellableException()
        status = OrderStatus.CANCELLED
        domainEvents += OrderCancelledEvent(orderId, reason, LocalDateTime.now())
    }

    fun totalAmount(): Int = items.sumOf { it.price * it.quantity }

    fun pullDomainEvents(): List<Any> = domainEvents.toList().also { domainEvents.clear() }
}
