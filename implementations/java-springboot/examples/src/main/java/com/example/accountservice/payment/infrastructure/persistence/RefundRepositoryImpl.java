package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.query.RefundQuery;
import com.example.accountservice.payment.domain.Refund;
import com.example.accountservice.payment.domain.RefundFindQuery;
import com.example.accountservice.payment.domain.RefundRepository;
import com.example.accountservice.payment.domain.RefundsWithCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund의 쓰기용 {@link RefundRepository}와 읽기용 {@link RefundQuery}를 한 클래스에서 구현한다 — {@link
 * PaymentRepositoryImpl}과 동일한 구조.
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
