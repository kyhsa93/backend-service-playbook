package com.example.accountservice.account.application.query

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class GetSpendingAnalysisResult(
    @field:Schema(description = "The analyzed month, in YYYY-MM form.", example = "2026-07")
    val analysisMonth: String,
    @field:Schema(description = "The account's total withdrawal amount for the month.", example = "50000")
    val totalAmount: Long,
    @field:Schema(description = "The number of withdrawal transactions in the month.", example = "2")
    val transactionCount: Long,
    @field:Schema(description = "The average withdrawal amount per transaction.", example = "25000")
    val averageAmount: Long,
    @field:Schema(description = "The percentage change in total withdrawal amount versus the previous month.", example = "50")
    val changeFromPreviousMonth: Long,
    @field:Schema(description = "A simple classification of the change.", example = "INCREASING")
    val trend: String,
    @field:Schema(description = "When this analysis was computed.")
    val createdAt: LocalDateTime,
)
