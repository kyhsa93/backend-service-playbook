package com.example.accountservice.account.application.command;

public record DepositCommand(String accountId, String requesterId, long amount) {}
