package com.example.orderservice.order.application.command

import com.example.orderservice.order.domain.Order
import com.example.orderservice.order.domain.OrderItem
import com.example.orderservice.order.domain.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateOrderService(private val orderRepository: OrderRepository) {

    fun create(command: CreateOrderCommand) {
        val items = command.items.map { OrderItem(it.itemId, it.name, it.price, it.quantity) }
        val order = Order.create(command.userId, items)
        orderRepository.save(order)
    }
}
