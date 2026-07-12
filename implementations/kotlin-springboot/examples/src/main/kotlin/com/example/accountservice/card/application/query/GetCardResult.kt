package com.example.accountservice.card.application.query

import java.time.LocalDateTime

data class GetCardResult(
    val cardId: String,
    val accountId: String,
    val ownerId: String,
    val brand: String,
    val status: String,
    val createdAt: LocalDateTime,
)
