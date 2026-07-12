package com.example.accountservice.card.interfaces.rest

import jakarta.validation.constraints.NotBlank

data class IssueCardRequest(
    @field:NotBlank
    val accountId: String,
    @field:NotBlank
    val brand: String,
)
