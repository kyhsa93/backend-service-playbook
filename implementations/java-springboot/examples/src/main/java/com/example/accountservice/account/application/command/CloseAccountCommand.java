package com.example.accountservice.account.application.command;

public record CloseAccountCommand(String accountId, String requesterId) {}
