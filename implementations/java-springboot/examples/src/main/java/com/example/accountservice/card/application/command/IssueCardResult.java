package com.example.accountservice.card.application.command;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record IssueCardResult(
        @Schema(description = "The generated card ID.") String cardId,
        @Schema(description = "The accountId this card is linked to.") String accountId,
        @Schema(description = "The userId of the card's owner.") String ownerId,
        @Schema(description = "The card network/brand.", example = "VISA") String brand,
        @Schema(description = "The card's status.", example = "ACTIVE") String status,
        @Schema(description = "When the card was issued.") LocalDateTime createdAt) {}
