package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record AccountReactivatedEvent(String accountId, String email, LocalDateTime reactivatedAt) {}
