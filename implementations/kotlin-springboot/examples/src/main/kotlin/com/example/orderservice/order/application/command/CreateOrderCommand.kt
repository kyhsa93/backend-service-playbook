package com.example.orderservice.order.application.command

data class CreateOrderCommand(
    val userId: String,
    val items: List<ItemInput>,
) {
    data class ItemInput(val itemId: Int, val name: String, val price: Int, val quantity: Int)
}
