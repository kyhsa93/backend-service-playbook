package com.example.accountservice.card.application.command;

public record IssueCardCommand(String accountId, String brand, String requesterId) {}
