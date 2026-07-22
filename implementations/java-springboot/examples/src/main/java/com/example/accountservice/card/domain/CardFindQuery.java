package com.example.accountservice.card.domain;

import java.util.List;

/**
 * Query criteria for {@code findCards} — single-record, filtered, and paginated lookups are all
 * expressed with this one record (the same pattern as account/domain/AccountFindQuery and
 * payment/domain/PaymentFindQuery). A single-record lookup (card detail, Payment BC's ACL query)
 * fills in {@code cardId}/{@code ownerId} and calls with {@code take} set to 1. Account BC's
 * Integration Event reactions (suspend/cancel) fill in only {@code accountId} + {@code statuses} to
 * look up the target card list.
 */
public record CardFindQuery(
        int page,
        int take,
        String cardId,
        String ownerId,
        String accountId,
        List<CardStatus> statuses) {}
