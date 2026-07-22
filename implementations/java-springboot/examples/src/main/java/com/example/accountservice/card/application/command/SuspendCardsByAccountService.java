package com.example.accountservice.card.application.command;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reaction use case for the Account BC's {@code account.suspended.v1} Integration Event.
 * Implemented idempotently under the assumption of at-least-once delivery — only ACTIVE cards are
 * selected for suspension, so redelivery of the same event (when the card is already suspended)
 * does nothing.
 */
@Service
@RequiredArgsConstructor
public class SuspendCardsByAccountService {

    private final CardRepository cardRepository;

    public void suspend(SuspendCardsByAccountCommand command) {
        List<Card> cards =
                cardRepository
                        .findCards(
                                new CardFindQuery(
                                        0,
                                        Integer.MAX_VALUE,
                                        null,
                                        null,
                                        command.accountId(),
                                        List.of(CardStatus.ACTIVE)))
                        .cards();
        for (Card card : cards) {
            card.suspend();
            cardRepository.saveCard(card);
        }
    }
}
