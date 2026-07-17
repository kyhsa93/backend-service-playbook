package com.example.accountservice.card.application.command;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SuspendCardsByAccountServiceTest {

    @Mock private CardRepository cardRepository;

    private SuspendCardsByAccountService service;

    @BeforeEach
    void setUp() {
        service = new SuspendCardsByAccountService(cardRepository);
    }

    @Test
    void 계좌의_ACTIVE_카드를_전부_정지하고_저장한다() {
        Card card1 = Card.issue("account-1", "owner-1", "VISA");
        Card card2 = Card.issue("account-1", "owner-1", "MASTER");
        when(cardRepository.findByAccountIdAndStatusIn("account-1", List.of(CardStatus.ACTIVE)))
                .thenReturn(List.of(card1, card2));

        service.suspend(new SuspendCardsByAccountCommand("account-1"));

        verify(cardRepository, times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ACTIVE_카드가_없으면_아무_것도_저장하지_않는다_멱등성() {
        when(cardRepository.findByAccountIdAndStatusIn(
                        eq("account-1"), eq(List.of(CardStatus.ACTIVE))))
                .thenReturn(List.of());

        service.suspend(new SuspendCardsByAccountCommand("account-1"));

        verify(cardRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
