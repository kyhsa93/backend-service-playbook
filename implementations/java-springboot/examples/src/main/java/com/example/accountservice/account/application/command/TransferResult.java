package com.example.accountservice.account.application.command;

public record TransferResult(
        String transferId,
        TransactionResult sourceTransaction,
        TransactionResult targetTransaction) {}
