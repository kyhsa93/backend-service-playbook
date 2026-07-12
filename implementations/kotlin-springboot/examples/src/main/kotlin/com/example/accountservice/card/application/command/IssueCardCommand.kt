package com.example.accountservice.card.application.command

data class IssueCardCommand(
    val accountId: String,
    val brand: String,
    val requesterId: String,
)
