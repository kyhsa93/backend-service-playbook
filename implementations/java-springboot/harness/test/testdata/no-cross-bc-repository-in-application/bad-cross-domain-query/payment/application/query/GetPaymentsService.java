package com.example.accountservice.payment.application.query;

import com.example.accountservice.card.application.query.CardQuery;

public class GetPaymentsService {
    private final CardQuery cardQuery;

    public GetPaymentsService(CardQuery cardQuery) {
        this.cardQuery = cardQuery;
    }
}
