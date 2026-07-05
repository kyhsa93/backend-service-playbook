package com.example.orderservice.order.infrastructure.persistence;

import com.example.orderservice.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderIdAndDeletedAtIsNull(String orderId);
}
