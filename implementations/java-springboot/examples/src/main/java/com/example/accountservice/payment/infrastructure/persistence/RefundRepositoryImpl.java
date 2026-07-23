package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.query.RefundQuery;
import com.example.accountservice.payment.domain.Refund;
import com.example.accountservice.payment.domain.RefundFindQuery;
import com.example.accountservice.payment.domain.RefundRepository;
import com.example.accountservice.payment.domain.RefundStatus;
import com.example.accountservice.payment.domain.RefundsWithCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements both the write-side {@link RefundRepository} and the read-side {@link RefundQuery} for
 * Refund in a single class — the same structure as {@link PaymentRepositoryImpl}.
 */
@Repository
@RequiredArgsConstructor
public class RefundRepositoryImpl implements RefundRepository, RefundQuery {

    private final RefundJpaRepository jpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    public RefundsWithCount findRefunds(RefundFindQuery query) {
        String listJpql = buildJpql(query, false);
        var listQuery =
                em.createQuery(listJpql, RefundJpaEntity.class)
                        .setFirstResult(query.page() * query.take())
                        .setMaxResults(query.take());
        applyParams(listQuery, query);
        List<Refund> refunds =
                listQuery.getResultList().stream().map(RefundMapper::toDomain).toList();

        String countJpql = buildJpql(query, true);
        var countQuery = em.createQuery(countJpql, Long.class);
        applyParams(countQuery, query);
        long count = countQuery.getSingleResult();

        return new RefundsWithCount(refunds, count);
    }

    @Override
    @Transactional
    public void saveRefund(Refund refund) {
        RefundJpaEntity entity =
                jpaRepository
                        .findByRefundId(refund.getRefundId())
                        .map(existing -> RefundMapper.updateEntity(existing, refund))
                        .orElseGet(() -> RefundMapper.toNewEntity(refund));
        jpaRepository.save(entity);
        outboxWriter.saveAll(refund.pullDomainEvents());
    }

    @Override
    public long summarizeRefundsByOwner(
            String ownerId, LocalDateTime createdAtFrom, RefundStatus status) {
        // RefundJpaEntity carries no ownerId of its own (only paymentId) — a theta-join against
        // PaymentJpaEntity via paymentId is required to filter by owner (the same reason this
        // method exists at all; see RefundRepository#summarizeRefundsByOwner).
        StringBuilder jpql =
                new StringBuilder(
                        "SELECT COUNT(r) FROM RefundJpaEntity r, PaymentJpaEntity p "
                                + "WHERE p.paymentId = r.paymentId AND p.ownerId = :ownerId "
                                + "AND r.createdAt >= :createdAtFrom");
        if (status != null) {
            jpql.append(" AND r.status = :status");
        }

        var countQuery =
                em.createQuery(jpql.toString(), Long.class)
                        .setParameter("ownerId", ownerId)
                        .setParameter("createdAtFrom", createdAtFrom);
        if (status != null) {
            countQuery.setParameter("status", status);
        }
        return countQuery.getSingleResult();
    }

    private String buildJpql(RefundFindQuery query, boolean count) {
        StringBuilder sb =
                new StringBuilder(
                        count
                                ? "SELECT COUNT(r) FROM RefundJpaEntity r"
                                : "SELECT r FROM RefundJpaEntity r");
        List<String> conditions = new ArrayList<>();
        if (query.refundId() != null && !query.refundId().isBlank()) {
            conditions.add("r.refundId = :refundId");
        }
        if (query.paymentId() != null && !query.paymentId().isBlank()) {
            conditions.add("r.paymentId = :paymentId");
        }
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!count) {
            sb.append(" ORDER BY r.createdAt DESC");
        }
        return sb.toString();
    }

    private void applyParams(Query q, RefundFindQuery query) {
        if (query.refundId() != null && !query.refundId().isBlank()) {
            q.setParameter("refundId", query.refundId());
        }
        if (query.paymentId() != null && !query.paymentId().isBlank()) {
            q.setParameter("paymentId", query.paymentId());
        }
    }
}
