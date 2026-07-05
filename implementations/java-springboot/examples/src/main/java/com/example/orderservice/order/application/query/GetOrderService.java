package com.example.orderservice.order.application.query;

import com.example.orderservice.order.domain.Order;
import com.example.orderservice.order.domain.OrderRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetOrderService {

    private final OrderRepository orderRepository;

    public GetOrderResult getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없습니다."));
        return new GetOrderResult(order.getOrderId(), order.getStatus().name(), order.totalAmount());
    }
}
