package com.example.accountservice.account.application.command;

public record WithdrawCommand(String accountId, String requesterId, long amount) {}
