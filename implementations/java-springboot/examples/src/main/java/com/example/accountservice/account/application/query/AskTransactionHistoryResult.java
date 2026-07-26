package com.example.accountservice.account.application.query;

import io.swagger.v3.oas.annotations.media.Schema;

public record AskTransactionHistoryResult(
        @Schema(
                        description =
                                "A natural-language answer grounded only in the requester's own"
                                        + " matching transactions.")
                String answer,
        @Schema(description = "How many transactions matched the question's translated filter.")
                long matchedCount) {}
