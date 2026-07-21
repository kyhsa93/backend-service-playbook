package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.outbox.OutboxWriter;
import com.example.accountservice.payment.application.query.PaymentQuery;
import com.example.accountservice.payment.application.query.PaymentUsageSummary;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import com.example.accountservice.payment.domain.PaymentStatus;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment의 쓰기용 {@link PaymentRepository}와 읽기용 {@link PaymentQuery}를 한 클래스에서 구현한다
 * (account/infrastructure/persistence/AccountRepositoryImpl과 동일한 구조). 각 Application 레이어는 자신에게 필요한
 * 좁은 인터페이스(Repository 또는 Query)만 주입받는다.
 */
@Repository
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository, PaymentQuery {

    private final PaymentJpaRepository jpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    public PaymentsWithCount findPayments(PaymentFindQuery query) {
        String listJpql = buildJpql(query, false);
        var listQuery =
                em.createQuery(listJpql, PaymentJpaEntity.class)
                        .setFirstResult(query.page() * query.take())
                        .setMaxResults(query.take());
        applyParams(listQuery, query);
        List<Payment> payments =
                listQuery.getResultList().stream().map(PaymentMapper::toDomain).toList();

        String countJpql = buildJpql(query, true);
        var countQuery = em.createQuery(countJpql, Long.class);
        applyParams(countQuery, query);
        long count = countQuery.getSingleResult();

        return new PaymentsWithCount(payments, count);
    }

    @Override
    @Transactional
    public void savePayment(Payment payment) {
        PaymentJpaEntity entity =
                jpaRepository
                        .findByPaymentId(payment.getPaymentId())
                        .map(existing -> PaymentMapper.updateEntity(existing, payment))
                        .orElseGet(() -> PaymentMapper.toNewEntity(payment));
        jpaRepository.save(entity);
        // Aggregate 저장과 같은 물리 트랜잭션 안에서 Outbox에 이벤트를 기록한다(domain-events.md 참고).
        outboxWriter.saveAll(payment.pullDomainEvents());
    }

    @Override
    public PaymentUsageSummary summarizeCardUsage(
            String cardId, LocalDateTime from, LocalDateTime to) {
        String jpql =
                "SELECT COUNT(p), COALESCE(SUM(p.amount), 0) FROM PaymentJpaEntity p "
                        + "WHERE p.cardId = :cardId AND p.status = :status "
                        + "AND p.createdAt >= :from AND p.createdAt < :to";
        Object[] result =
                em.createQuery(jpql, Object[].class)
                        .setParameter("cardId", cardId)
                        .setParameter("status", PaymentStatus.COMPLETED)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult();
        return new PaymentUsageSummary((Long) result[0], (Long) result[1]);
    }

    private String buildJpql(PaymentFindQuery query, boolean count) {
        StringBuilder sb =
                new StringBuilder(
                        count
                                ? "SELECT COUNT(p) FROM PaymentJpaEntity p"
                                : "SELECT p FROM PaymentJpaEntity p");
        List<String> conditions = new ArrayList<>();
        if (query.paymentId() != null && !query.paymentId().isBlank()) {
            conditions.add("p.paymentId = :paymentId");
        }
        if (query.ownerId() != null && !query.ownerId().isBlank()) {
            conditions.add("p.ownerId = :ownerId");
        }
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!count) {
            sb.append(" ORDER BY p.createdAt DESC");
        }
        return sb.toString();
    }

    private void applyParams(Query q, PaymentFindQuery query) {
        if (query.paymentId() != null && !query.paymentId().isBlank()) {
            q.setParameter("paymentId", query.paymentId());
        }
        if (query.ownerId() != null && !query.ownerId().isBlank()) {
            q.setParameter("ownerId", query.ownerId());
        }
    }
}
