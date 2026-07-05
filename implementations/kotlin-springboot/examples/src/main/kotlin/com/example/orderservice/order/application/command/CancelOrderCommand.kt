package com.example.orderservice.order.application.command

data class CancelOrderCommand(val orderId: String, val reason: String)
