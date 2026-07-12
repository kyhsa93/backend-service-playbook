package com.example.accountservice.card.application.command;

import java.time.LocalDateTime;

public record IssueCardResult(
        String cardId,
        String accountId,
        String ownerId,
        String brand,
        String status,
        LocalDateTime createdAt
) {}
