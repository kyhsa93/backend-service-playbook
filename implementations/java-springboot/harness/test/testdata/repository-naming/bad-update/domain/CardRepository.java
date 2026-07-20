package com.example.accountservice.card.domain;

public interface CardRepository {
    CardsWithCount findCards(CardFindQuery query);

    void updateCardStatus(String cardId, String status);
}
