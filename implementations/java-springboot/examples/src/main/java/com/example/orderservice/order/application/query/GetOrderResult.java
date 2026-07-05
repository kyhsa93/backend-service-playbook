package com.example.orderservice.order.application.query;

public record GetOrderResult(String orderId, String status, int totalAmount) {}
