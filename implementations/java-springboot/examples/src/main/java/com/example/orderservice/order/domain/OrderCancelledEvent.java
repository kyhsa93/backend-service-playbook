package com.example.orderservice.order.domain;

import java.time.LocalDateTime;

public record OrderCancelledEvent(String orderId, String reason, LocalDateTime cancelledAt) {}
