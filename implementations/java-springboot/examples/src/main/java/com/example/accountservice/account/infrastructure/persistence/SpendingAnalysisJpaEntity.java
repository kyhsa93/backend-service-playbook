package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.SpendingTrend;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * The JPA-mapping counterpart of account/domain/SpendingAnalysis.java. The Domain object
 * (SpendingAnalysis) has no knowledge of this class at all — the conversion is handled entirely by
 * SpendingAnalysisMapper. One row per (accountId, analysisMonth) — the unique constraint is the
 * idempotency backstop, the same role as card_sent_email's uniqueness check. Insert-only (like
 * TransactionJpaEntity/CardSentEmail): a row is never updated or soft-deleted after it's written,
 * so unlike AccountJpaEntity there is no updatedAt/deletedAt column.
 */
@Entity
@Table(
        name = "spending_analysis",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_spending_analysis_account_month",
                        columnNames = {"accountId", "analysisMonth"}))
public class SpendingAnalysisJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String analysisId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String analysisMonth;

    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private long transactionCount;

    @Column(nullable = false)
    private long averageAmount;

    @Column(nullable = false)
    private int changeFromPreviousMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpendingTrend trend;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected SpendingAnalysisJpaEntity() {}

    SpendingAnalysisJpaEntity(
            Long id,
            String analysisId,
            String accountId,
            String analysisMonth,
            long totalAmount,
            long transactionCount,
            long averageAmount,
            int changeFromPreviousMonth,
            SpendingTrend trend,
            LocalDateTime createdAt) {
        this.id = id;
        this.analysisId = analysisId;
        this.accountId = accountId;
        this.analysisMonth = analysisMonth;
        this.totalAmount = totalAmount;
        this.transactionCount = transactionCount;
        this.averageAmount = averageAmount;
        this.changeFromPreviousMonth = changeFromPreviousMonth;
        this.trend = trend;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getAnalysisId() {
        return analysisId;
    }

    String getAccountId() {
        return accountId;
    }

    String getAnalysisMonth() {
        return analysisMonth;
    }

    long getTotalAmount() {
        return totalAmount;
    }

    long getTransactionCount() {
        return transactionCount;
    }

    long getAverageAmount() {
        return averageAmount;
    }

    int getChangeFromPreviousMonth() {
        return changeFromPreviousMonth;
    }

    SpendingTrend getTrend() {
        return trend;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
