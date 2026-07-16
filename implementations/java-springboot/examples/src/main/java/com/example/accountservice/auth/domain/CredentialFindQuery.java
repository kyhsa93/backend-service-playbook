package com.example.accountservice.auth.domain;

public record CredentialFindQuery(int page, int take, String userId) {}
