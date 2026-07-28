package com.example.accountservice.payment.infrastructure.persistence;

import com.example.accountservice.payment.application.query.RefundReasonCategoryCount;
import com.example.accountservice.payment.application.query.RefundReasonInsightsQuery;
import com.example.accountservice.payment.application.query.RefundReasonInsightsResult;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RefundReasonInsightsQueryImpl implements RefundReasonInsightsQuery {

    private final EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public RefundReasonInsightsResult getInsights(LocalDate fromDate, LocalDate toDate) {
        StringBuilder jpql =
                new StringBuilder(
                        "SELECT r.reasonCategory, COUNT(r) FROM RefundJpaEntity r"
                                + " WHERE r.reasonCategory IS NOT NULL");
        if (fromDate != null) {
            jpql.append(" AND r.createdAt >= :fromDateTime");
        }
        if (toDate != null) {
            jpql.append(" AND r.createdAt <= :toDateTime");
        }
        jpql.append(" GROUP BY r.reasonCategory");

        Query query = em.createQuery(jpql.toString());
        if (fromDate != null) {
            query.setParameter("fromDateTime", fromDate.atStartOfDay());
        }
        if (toDate != null) {
            query.setParameter("toDateTime", LocalDateTime.of(toDate, LocalTime.MAX));
        }

        List<Object[]> rows = query.getResultList();
        List<RefundReasonCategoryCount> counts =
                rows.stream()
                        .map(
                                row ->
                                        new RefundReasonCategoryCount(
                                                ((RefundReasonCategory) row[0]).name(),
                                                (Long) row[1]))
                        .toList();
        long totalClassified = counts.stream().mapToLong(RefundReasonCategoryCount::count).sum();

        return new RefundReasonInsightsResult(counts, totalClassified);
    }
}
