package com.example.orderservice.order.domain

interface OrderRepository {
    fun findById(orderId: String): Order?
    fun findAll(query: OrderFindQuery): List<Order>
    fun countAll(query: OrderFindQuery): Long
    fun save(order: Order)
    fun delete(orderId: String)
}

data class OrderFindQuery(
    val page: Int,
    val take: Int,
    val userId: String? = null,
    val status: List<String>? = null,
)
