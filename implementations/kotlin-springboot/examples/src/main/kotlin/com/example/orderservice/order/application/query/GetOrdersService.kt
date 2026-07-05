package com.example.orderservice.order.application.query

import com.example.orderservice.order.domain.OrderFindQuery
import com.example.orderservice.order.domain.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetOrdersService(private val orderRepository: OrderRepository) {

    fun getOrders(page: Int, take: Int, userId: String?, status: List<String>?): GetOrdersResult {
        val query = OrderFindQuery(page, take, userId, status)
        val orders = orderRepository.findAll(query)
        val total = orderRepository.countAll(query)
        return GetOrdersResult(
            orders = orders.map { GetOrdersResult.OrderSummary(it.orderId, it.status.name, it.totalAmount()) },
            totalCount = total,
        )
    }
}
