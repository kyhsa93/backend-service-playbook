package com.example.orderservice.order.application.query

import com.example.orderservice.order.domain.OrderNotFoundException
import com.example.orderservice.order.domain.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetOrderService(private val orderRepository: OrderRepository) {

    fun getOrder(orderId: String): GetOrderResult {
        val order = orderRepository.findById(orderId)
            ?: throw OrderNotFoundException(orderId)
        return GetOrderResult(order.orderId, order.status.name, order.totalAmount())
    }
}
