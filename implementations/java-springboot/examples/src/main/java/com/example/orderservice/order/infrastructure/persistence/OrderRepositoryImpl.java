package com.example.orderservice.order.infrastructure.persistence;

import com.example.orderservice.order.domain.Order;
import com.example.orderservice.order.domain.OrderFindQuery;
import com.example.orderservice.order.domain.OrderRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpaRepository;
    private final EntityManager em;

    @Override
    public Optional<Order> findById(String orderId) {
        return jpaRepository.findByOrderIdAndDeletedAtIsNull(orderId);
    }

    @Override
    public List<Order> findAll(OrderFindQuery query) {
        String jpql = buildJpql(query, false);
        var q = em.createQuery(jpql, Order.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        return q.getResultList();
    }

    @Override
    public long countAll(OrderFindQuery query) {
        String jpql = buildJpql(query, true);
        var q = em.createQuery(jpql, Long.class);
        applyParams(q, query);
        return q.getSingleResult();
    }

    @Override
    public void save(Order order) {
        jpaRepository.save(order);
    }

    @Override
    public void delete(String orderId) {
        jpaRepository.findByOrderIdAndDeletedAtIsNull(orderId).ifPresent(order -> {
            em.createQuery("UPDATE Order o SET o.deletedAt = :now WHERE o.orderId = :id")
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("id", orderId)
                    .executeUpdate();
        });
    }

    private String buildJpql(OrderFindQuery query, boolean count) {
        StringBuilder sb = new StringBuilder(count
                ? "SELECT COUNT(o) FROM Order o WHERE o.deletedAt IS NULL"
                : "SELECT o FROM Order o WHERE o.deletedAt IS NULL");
        if (query.userId() != null && !query.userId().isBlank()) sb.append(" AND o.userId = :userId");
        if (query.status() != null && !query.status().isEmpty()) sb.append(" AND o.status IN :status");
        if (!count) sb.append(" ORDER BY o.orderId DESC");
        return sb.toString();
    }

    private void applyParams(jakarta.persistence.Query q, OrderFindQuery query) {
        if (query.userId() != null && !query.userId().isBlank()) q.setParameter("userId", query.userId());
        if (query.status() != null && !query.status().isEmpty()) q.setParameter("status",
                query.status().stream().map(com.example.orderservice.order.domain.OrderStatus::valueOf).toList());
    }
}
