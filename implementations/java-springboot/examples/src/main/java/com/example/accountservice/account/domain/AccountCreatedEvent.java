package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record AccountCreatedEvent(
        String accountId, String ownerId, String email, String currency, LocalDateTime createdAt) {}
