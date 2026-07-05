package com.example.orderservice.order.application.query

data class GetOrderResult(val orderId: String, val status: String, val totalAmount: Int)

data class GetOrdersResult(val orders: List<OrderSummary>, val totalCount: Long) {
    data class OrderSummary(val orderId: String, val status: String, val totalAmount: Int)
}
