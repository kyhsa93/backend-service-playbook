package com.example.accountservice.account.application.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record SpendingForecastResult(
        @Schema(description = "The forecasted month, in YYYY-MM form.") String forecastMonth,
        @Schema(description = "The model's predicted total withdrawal amount for the month.")
                long predictedAmount,
        @Schema(
                        description =
                                "The model's confidence in the prediction, based on how well a"
                                        + " linear trend fits the account's history.",
                        example = "HIGH")
                String confidence,
        @Schema(
                        description =
                                "How many months of history the model was trained on for this"
                                        + " prediction.")
                int historyMonthsUsed,
        @Schema(description = "When this forecast was computed.") LocalDateTime createdAt) {}
