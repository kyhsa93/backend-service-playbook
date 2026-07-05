package com.example.orderservice.order.application.command

import com.example.orderservice.order.domain.OrderNotFoundException
import com.example.orderservice.order.domain.OrderRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CancelOrderService(
    private val orderRepository: OrderRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun cancel(command: CancelOrderCommand) {
        val order = orderRepository.findById(command.orderId)
            ?: throw OrderNotFoundException(command.orderId)
        order.cancel(command.reason)
        orderRepository.save(order)
        order.pullDomainEvents().forEach(eventPublisher::publishEvent)
    }
}
