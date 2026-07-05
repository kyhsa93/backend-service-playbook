package com.example.orderservice.order.domain

import java.time.LocalDateTime

data class OrderCancelledEvent(
    val orderId: String,
    val reason: String,
    val cancelledAt: LocalDateTime,
)
