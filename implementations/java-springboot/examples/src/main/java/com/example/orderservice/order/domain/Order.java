package com.example.orderservice.order.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderId;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id", referencedColumnName = "order_id"))
    private List<OrderItem> items;

    @Column
    private LocalDateTime deletedAt;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    protected Order() {}

    public static Order create(String userId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문 항목은 최소 1개 이상이어야 합니다.");
        }
        Order order = new Order();
        order.orderId = UUID.randomUUID().toString();
        order.userId = userId;
        order.items = new ArrayList<>(items);
        order.status = OrderStatus.PENDING;
        return order;
    }

    public void cancel(String reason) {
        if (this.status == OrderStatus.CANCELLED) throw new IllegalStateException("이미 취소된 주문입니다.");
        if (this.status == OrderStatus.PAID) throw new IllegalStateException("결제 완료된 주문은 취소할 수 없습니다.");
        this.status = OrderStatus.CANCELLED;
        this.domainEvents.add(new OrderCancelledEvent(this.orderId, reason, LocalDateTime.now()));
    }

    public int totalAmount() {
        return items.stream().mapToInt(i -> i.price() * i.quantity()).sum();
    }

    public List<Object> pullDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public List<OrderItem> getItems() { return List.copyOf(items); }
    public LocalDateTime getDeletedAt() { return deletedAt; }
}
