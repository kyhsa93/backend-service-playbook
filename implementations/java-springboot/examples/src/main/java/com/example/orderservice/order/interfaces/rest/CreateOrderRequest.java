package com.example.orderservice.order.interfaces.rest;

import java.util.List;

public record CreateOrderRequest(String userId, List<ItemInput> items) {
    public record ItemInput(int itemId, String name, int price, int quantity) {}
}
