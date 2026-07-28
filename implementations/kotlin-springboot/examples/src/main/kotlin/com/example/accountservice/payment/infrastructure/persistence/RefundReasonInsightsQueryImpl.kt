package com.example.accountservice.payment.infrastructure.persistence

import com.example.accountservice.payment.application.query.RefundReasonInsightsQuery
import com.example.accountservice.payment.application.query.RefundReasonInsightsResult
import com.example.accountservice.payment.domain.RefundReasonCategory
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Repository
class RefundReasonInsightsQueryImpl(
    private val em: EntityManager,
) : RefundReasonInsightsQuery {
    @Suppress("UNCHECKED_CAST")
    override fun getInsights(
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): RefundReasonInsightsResult {
        val jpql = StringBuilder("SELECT r.reasonCategory, COUNT(r) FROM RefundJpaEntity r WHERE r.reasonCategory IS NOT NULL")
        if (fromDate != null) jpql.append(" AND r.createdAt >= :fromDateTime")
        if (toDate != null) jpql.append(" AND r.createdAt <= :toDateTime")
        jpql.append(" GROUP BY r.reasonCategory")

        val query = em.createQuery(jpql.toString())
        if (fromDate != null) query.setParameter("fromDateTime", fromDate.atStartOfDay())
        if (toDate != null) query.setParameter("toDateTime", LocalDateTime.of(toDate, LocalTime.MAX))

        val rows = query.resultList as List<Array<Any>>
        val counts =
            rows.map { row ->
                RefundReasonInsightsResult.RefundReasonCategoryCount(
                    category = (row[0] as RefundReasonCategory).name,
                    count = row[1] as Long,
                )
            }
        val totalClassified = counts.sumOf { it.count }

        return RefundReasonInsightsResult(counts = counts, totalClassified = totalClassified)
    }
}
