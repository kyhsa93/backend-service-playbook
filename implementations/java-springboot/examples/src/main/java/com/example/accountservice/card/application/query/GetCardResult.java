package com.example.accountservice.card.application.query;

import java.time.LocalDateTime;

public record GetCardResult(
        String cardId,
        String accountId,
        String ownerId,
        String brand,
        String status,
        LocalDateTime createdAt
) {}
