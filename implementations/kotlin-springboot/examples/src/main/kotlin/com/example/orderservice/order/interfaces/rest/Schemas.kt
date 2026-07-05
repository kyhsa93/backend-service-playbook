package com.example.orderservice.order.interfaces.rest

data class CreateOrderRequest(
    val userId: String,
    val items: List<ItemInput>,
) {
    data class ItemInput(val itemId: Int, val name: String, val price: Int, val quantity: Int)
}

data class CancelOrderRequest(val reason: String)

data class ErrorResponse(val message: String)
