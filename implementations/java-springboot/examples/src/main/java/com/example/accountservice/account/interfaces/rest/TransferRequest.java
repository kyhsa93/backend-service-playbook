package com.example.accountservice.account.interfaces.rest;

public record TransferRequest(String targetAccountId, long amount) {}
