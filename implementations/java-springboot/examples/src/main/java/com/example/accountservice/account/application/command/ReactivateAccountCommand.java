package com.example.accountservice.account.application.command;

public record ReactivateAccountCommand(String accountId, String requesterId) {}
