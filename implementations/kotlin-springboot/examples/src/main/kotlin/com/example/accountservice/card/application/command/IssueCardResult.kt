package com.example.accountservice.card.application.command

import java.time.LocalDateTime

data class IssueCardResult(
    val cardId: String,
    val accountId: String,
    val ownerId: String,
    val brand: String,
    val status: String,
    val createdAt: LocalDateTime,
)
