package com.example.accountservice.auth.domain;

import java.util.List;

public record CredentialsWithCount(List<Credential> credentials, long count) {}
