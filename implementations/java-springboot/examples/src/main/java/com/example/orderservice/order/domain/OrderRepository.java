package com.example.orderservice.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(String orderId);
    List<Order> findAll(OrderFindQuery query);
    long countAll(OrderFindQuery query);
    void save(Order order);
    void delete(String orderId);
}
