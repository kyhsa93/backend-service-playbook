package com.example.accountservice.card.domain;

public interface CardRepository {
    CardsWithCount findCards(CardFindQuery query);

    void delete(String cardId);
}
