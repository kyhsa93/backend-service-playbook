package com.example.accountservice.account.application.query

import io.swagger.v3.oas.annotations.media.Schema

data class AskTransactionHistoryResult(
    @field:Schema(description = "A natural-language answer grounded only in the requester's own matching transactions.")
    val answer: String,
    @field:Schema(description = "How many transactions matched the question's translated filter.", example = "3")
    val matchedCount: Long,
)
