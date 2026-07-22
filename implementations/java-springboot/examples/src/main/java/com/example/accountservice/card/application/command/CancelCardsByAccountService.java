package com.example.accountservice.card.application.command;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Reaction use case for the Account BC's {@code account.closed.v1} Integration Event. Only cancels
 * cards that are not already cancelled (ACTIVE·SUSPENDED), making it idempotent against redelivery.
 */
@Service
@RequiredArgsConstructor
public class CancelCardsByAccountService {

    private final CardRepository cardRepository;

    public void cancel(CancelCardsByAccountCommand command) {
        List<Card> cards =
                cardRepository
                        .findCards(
                                new CardFindQuery(
                                        0,
                                        Integer.MAX_VALUE,
                                        null,
                                        null,
                                        command.accountId(),
                                        List.of(CardStatus.ACTIVE, CardStatus.SUSPENDED)))
                        .cards();
        for (Card card : cards) {
            card.cancel();
            cardRepository.saveCard(card);
        }
    }
}
