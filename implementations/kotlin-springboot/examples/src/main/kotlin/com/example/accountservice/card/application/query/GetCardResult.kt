package com.example.accountservice.card.application.query

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class GetCardResult(
    @field:Schema(description = "The card's ID (32-character hex, no hyphens).", example = "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1")
    val cardId: String,
    @field:Schema(description = "The account this card is linked to.", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    val accountId: String,
    @field:Schema(description = "The userId of the card owner.", example = "jane.doe")
    val ownerId: String,
    @field:Schema(description = "The card network brand.", example = "VISA")
    val brand: String,
    @field:Schema(description = "The card's lifecycle status.", example = "ACTIVE")
    val status: String,
    @field:Schema(description = "When the card was issued.")
    val createdAt: LocalDateTime,
)
