package com.example.accountservice.card.application.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.card.application.adapter.AccountAdapter;
import com.example.accountservice.card.application.adapter.PaymentAdapter;
import com.example.accountservice.card.application.service.NotificationService;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import com.example.accountservice.card.domain.CardsWithCount;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SendCardStatementServiceTest {

    @Mock private CardRepository cardRepository;
    @Mock private AccountAdapter accountAdapter;
    @Mock private PaymentAdapter paymentAdapter;
    @Mock private NotificationService notificationService;

    private SendCardStatementService service;

    @BeforeEach
    void setUp() {
        service =
                new SendCardStatementService(
                        cardRepository, accountAdapter, paymentAdapter, notificationService);
    }

    private CardFindQuery activeCardsQuery() {
        return new CardFindQuery(0, 100, null, null, null, List.of(CardStatus.ACTIVE));
    }

    @Test
    void 이번_달_안내를_아직_보내지_않은_카드에_사용내역_안내를_보내고_기록한다() {
        Card card = Card.issue("account-1", "owner-1", "VISA");
        YearMonth month = YearMonth.of(2026, 7);
        when(cardRepository.findCards(activeCardsQuery()))
                .thenReturn(new CardsWithCount(List.of(card), 1));
        when(accountAdapter.findAccount("account-1", "owner-1"))
                .thenReturn(
                        Optional.of(
                                new AccountAdapter.AccountView(
                                        "account-1", true, "owner1@example.com")));
        when(paymentAdapter.summarizeUsage(eq(card.getCardId()), any(), any()))
                .thenReturn(new PaymentAdapter.UsageSummary(3, 45_000));

        service.sendStatements(new SendCardStatementCommand(month));

        verify(notificationService)
                .sendEmail(
                        eq(card.getCardId()),
                        eq("CardStatement"),
                        eq("owner1@example.com"),
                        any(),
                        any());
        verify(cardRepository).saveCard(card);
        org.assertj.core.api.Assertions.assertThat(card.getLastStatementSentMonth())
                .isEqualTo(month);
    }

    @Test
    void 이미_이번_달_안내를_보낸_카드는_다시_보내지_않는다_멱등성() {
        Card card = Card.issue("account-1", "owner-1", "VISA");
        YearMonth month = YearMonth.of(2026, 7);
        card.markStatementSent(month);
        when(cardRepository.findCards(activeCardsQuery()))
                .thenReturn(new CardsWithCount(List.of(card), 1));

        service.sendStatements(new SendCardStatementCommand(month));

        verify(notificationService, never()).sendEmail(any(), any(), any(), any(), any());
        verify(cardRepository, never()).saveCard(any());
    }

    @Test
    void 연결_계좌를_찾을_수_없으면_건너뛰고_기록하지_않는다() {
        Card card = Card.issue("account-1", "owner-1", "VISA");
        YearMonth month = YearMonth.of(2026, 7);
        when(cardRepository.findCards(activeCardsQuery()))
                .thenReturn(new CardsWithCount(List.of(card), 1));
        when(accountAdapter.findAccount("account-1", "owner-1")).thenReturn(Optional.empty());

        service.sendStatements(new SendCardStatementCommand(month));

        verify(notificationService, never()).sendEmail(any(), any(), any(), any(), any());
        verify(cardRepository, never()).saveCard(any());
    }

    @Test
    void 사용내역_기간은_해당_월_1일부터_다음_달_1일_직전까지다() {
        Card card = Card.issue("account-1", "owner-1", "VISA");
        YearMonth month = YearMonth.of(2026, 7);
        when(cardRepository.findCards(activeCardsQuery()))
                .thenReturn(new CardsWithCount(List.of(card), 1));
        when(accountAdapter.findAccount("account-1", "owner-1"))
                .thenReturn(
                        Optional.of(
                                new AccountAdapter.AccountView(
                                        "account-1", true, "owner1@example.com")));
        when(paymentAdapter.summarizeUsage(any(), any(), any()))
                .thenReturn(new PaymentAdapter.UsageSummary(0, 0));

        service.sendStatements(new SendCardStatementCommand(month));

        verify(paymentAdapter)
                .summarizeUsage(
                        card.getCardId(),
                        LocalDateTime.of(2026, 7, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 1, 0, 0));
    }
}
