package com.example.accountservice.card.domain;

import java.time.YearMonth;

public class Card {
    private YearMonth lastStatementSentMonth;

    private Card() {
    }

    public void markStatementSent() {
        this.lastStatementSentMonth = YearMonth.now();
    }
}
