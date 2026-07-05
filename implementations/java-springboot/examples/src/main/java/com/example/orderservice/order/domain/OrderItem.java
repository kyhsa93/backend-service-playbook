package com.example.orderservice.order.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public record OrderItem(int itemId, String name, int price, int quantity) {
    public OrderItem {
        if (price <= 0) throw new IllegalArgumentException("상품 가격은 0보다 커야 합니다.");
        if (quantity <= 0) throw new IllegalArgumentException("수량은 0보다 커야 합니다.");
    }
}
