package com.example.accountservice.account.domain;

import java.util.List;

public record AccountFindQuery(
        int page, int take, String accountId, String ownerId, List<String> status) {}
