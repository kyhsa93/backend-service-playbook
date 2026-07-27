package com.example.accountservice.account.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record SpendingAnalysisResult(
        @Schema(description = "The analyzed month, in YYYY-MM form.") String analysisMonth,
        @Schema(description = "The account's total withdrawal amount for the month.")
                long totalAmount,
        @Schema(description = "The number of withdrawal transactions in the month.")
                long transactionCount,
        @Schema(description = "The average withdrawal amount per transaction.") long averageAmount,
        @Schema(
                        description =
                                "The percentage change in total withdrawal amount versus the previous month.")
                int changeFromPreviousMonth,
        @Schema(description = "A simple classification of the change.", example = "INCREASING")
                String trend,
        @Schema(description = "When this analysis was computed.") LocalDateTime createdAt) {}
