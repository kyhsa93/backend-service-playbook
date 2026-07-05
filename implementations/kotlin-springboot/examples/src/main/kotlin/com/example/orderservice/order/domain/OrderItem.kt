package com.example.orderservice.order.domain

import jakarta.persistence.Embeddable

@Embeddable
data class OrderItem(
    val itemId: Int,
    val name: String,
    val price: Int,
    val quantity: Int,
) {
    init {
        require(price > 0) { "상품 가격은 0보다 커야 합니다." }
        require(quantity > 0) { "수량은 0보다 커야 합니다." }
    }
}
