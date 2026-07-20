package com.example.accountservice.card.domain;

public interface CardRepository {
    CardsWithCount findCards(CardFindQuery query);

    long countByOwnerId(String ownerId);
}
