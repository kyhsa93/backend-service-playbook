package com.example.accountservice.card.application.command;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import com.example.accountservice.card.domain.CardsWithCount;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelCardsByAccountServiceTest {

    @Mock private CardRepository cardRepository;

    private CancelCardsByAccountService service;

    @BeforeEach
    void setUp() {
        service = new CancelCardsByAccountService(cardRepository);
    }

    @Test
    void cancels_and_saves_all_of_the_accounts_ACTIVE_and_SUSPENDED_cards() {
        Card active = Card.issue("account-1", "owner-1", "VISA");
        Card suspended = Card.issue("account-1", "owner-1", "MASTER");
        suspended.suspend();
        when(cardRepository.findCards(
                        new CardFindQuery(
                                0,
                                Integer.MAX_VALUE,
                                null,
                                null,
                                "account-1",
                                List.of(CardStatus.ACTIVE, CardStatus.SUSPENDED))))
                .thenReturn(new CardsWithCount(List.of(active, suspended), 2));

        service.cancel(new CancelCardsByAccountCommand("account-1"));

        verify(cardRepository, times(2)).saveCard(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saves_nothing_when_there_are_no_cards_to_cancel_idempotency() {
        when(cardRepository.findCards(
                        eq(
                                new CardFindQuery(
                                        0,
                                        Integer.MAX_VALUE,
                                        null,
                                        null,
                                        "account-1",
                                        List.of(CardStatus.ACTIVE, CardStatus.SUSPENDED)))))
                .thenReturn(new CardsWithCount(List.of(), 0));

        service.cancel(new CancelCardsByAccountCommand("account-1"));

        verify(cardRepository, never()).saveCard(org.mockito.ArgumentMatchers.any());
    }
}
