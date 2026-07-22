package com.example.accountservice.card.interfaces.rest

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class IssueCardRequest(
    @field:NotBlank
    @field:Schema(
        description = "The accountId the new card is linked to. The account must belong to the requester and be active.",
        example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
    )
    val accountId: String,
    @field:NotBlank
    @field:Schema(description = "The card network brand.", example = "VISA")
    val brand: String,
)
