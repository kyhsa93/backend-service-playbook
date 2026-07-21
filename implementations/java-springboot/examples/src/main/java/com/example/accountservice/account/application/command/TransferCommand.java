package com.example.accountservice.account.application.command;

public record TransferCommand(
        String sourceAccountId, String targetAccountId, String requesterId, long amount) {}
