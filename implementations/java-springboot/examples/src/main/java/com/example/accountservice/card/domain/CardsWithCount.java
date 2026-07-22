package com.example.accountservice.card.domain;

import java.util.List;

/**
 * The result of a {@code findCards} query — returns the list together with the total count. A
 * single-record lookup also reuses this type: call with {@code CardFindQuery.take} set to 1, then
 * take the first result from {@code cards()} (see repository-pattern.md).
 */
public record CardsWithCount(List<Card> cards, long count) {}
