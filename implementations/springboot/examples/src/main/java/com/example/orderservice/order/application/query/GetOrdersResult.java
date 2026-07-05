package com.example.orderservice.order.application.query;

import java.util.List;

public record GetOrdersResult(List<OrderSummary> orders, long totalCount) {
    public record OrderSummary(String orderId, String status, int totalAmount) {}
}
