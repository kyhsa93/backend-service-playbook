package com.example.accountservice.account.domain;

import java.time.LocalDateTime;

public record AccountClosedEvent(String accountId, LocalDateTime closedAt) {}
