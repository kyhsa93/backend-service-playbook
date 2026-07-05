package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record AccountSuspendedEvent(String accountId, LocalDateTime suspendedAt) {}
