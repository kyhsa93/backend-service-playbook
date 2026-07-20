package com.example.accountservice.card.domain;

import com.example.accountservice.common.IdGenerator;

public class Card {
    private String cardId;
    private String accountId;

    public static Card issue(String accountId) {
        Card card = new Card();
        card.cardId = IdGenerator.generate();
        card.accountId = accountId;
        return card;
    }
}
