package com.example.accountservice.card.domain;

public interface CardRepository {
    java.util.List<Card> findAll();

    void saveCard(Card card);
}
