package com.example.accountservice.account.application.command;

public record SuspendAccountCommand(String accountId, String requesterId) {}
