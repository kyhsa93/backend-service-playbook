package com.example.orderservice.order.application.command;

import com.example.orderservice.order.domain.Order;
import com.example.orderservice.order.domain.OrderItem;
import com.example.orderservice.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CreateOrderService {

    private final OrderRepository orderRepository;

    public void create(CreateOrderCommand command) {
        List<OrderItem> items = command.items().stream()
                .map(i -> new OrderItem(i.itemId(), i.name(), i.price(), i.quantity()))
                .toList();
        Order order = Order.create(command.userId(), items);
        orderRepository.save(order);
    }
}
