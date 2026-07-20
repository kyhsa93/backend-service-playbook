package com.example.accountservice.card.domain;

public interface CardRepository {
    Card findByAccountIdAndStatusIn(String accountId, java.util.List<String> statuses);

    void saveCard(Card card);
}
