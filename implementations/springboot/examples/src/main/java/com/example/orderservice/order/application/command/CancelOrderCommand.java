package com.example.orderservice.order.application.command;

public record CancelOrderCommand(String orderId, String reason) {}
