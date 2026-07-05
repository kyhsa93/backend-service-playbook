package com.example.orderservice.order.application.command;

import java.util.List;

public record CreateOrderCommand(String userId, List<ItemInput> items) {
    public record ItemInput(int itemId, String name, int price, int quantity) {}
}
