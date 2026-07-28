package com.example.accountservice.account.application.query

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class GetSpendingForecastResult(
    @field:Schema(description = "The forecasted month, in YYYY-MM form.", example = "2026-07")
    val forecastMonth: String,
    @field:Schema(description = "The model's predicted total withdrawal amount for the month.", example = "40000")
    val predictedAmount: Long,
    @field:Schema(
        description = "The model's confidence in the prediction, based on how well a linear trend fits the account's history.",
        example = "HIGH",
    )
    val confidence: String,
    @field:Schema(description = "How many months of history the model was trained on for this prediction.", example = "3")
    val historyMonthsUsed: Int,
    @field:Schema(description = "When this forecast was computed.")
    val createdAt: LocalDateTime,
)
