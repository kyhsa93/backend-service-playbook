package com.example.accountservice.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CardTest {

    private Card issueCard() {
        return Card.issue("account-1", "owner-1", "VISA");
    }

    @Test
    void 발급하면_ACTIVE_상태다() {
        Card card = issueCard();

        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.getAccountId()).isEqualTo("account-1");
        assertThat(card.getOwnerId()).isEqualTo("owner-1");
        assertThat(card.getBrand()).isEqualTo("VISA");
    }

    @Test
    void 카드_ID는_하이픈_없는_32자리_hex_문자열이다() {
        Card card = issueCard();

        assertThat(card.getCardId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void 활성_카드를_정지하면_SUSPENDED_상태가_된다() {
        Card card = issueCard();

        card.suspend();

        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    void 이미_정지된_카드를_정지하면_예외를_던진다() {
        Card card = issueCard();
        card.suspend();

        assertThatThrownBy(card::suspend)
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CARD_ALREADY_SUSPENDED);
    }

    @Test
    void 해지된_카드를_정지하면_예외를_던진다() {
        Card card = issueCard();
        card.cancel();

        assertThatThrownBy(card::suspend)
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED);
    }

    @Test
    void 활성_카드를_해지하면_CANCELLED_상태가_된다() {
        Card card = issueCard();

        card.cancel();

        assertThat(card.getStatus()).isEqualTo(CardStatus.CANCELLED);
    }

    @Test
    void 정지된_카드를_해지하면_CANCELLED_상태가_된다() {
        Card card = issueCard();
        card.suspend();

        card.cancel();

        assertThat(card.getStatus()).isEqualTo(CardStatus.CANCELLED);
    }

    @Test
    void 이미_해지된_카드를_해지하면_예외를_던진다() {
        Card card = issueCard();
        card.cancel();

        assertThatThrownBy(card::cancel)
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CARD_ALREADY_CANCELLED);
    }

    @Test
    void 활성_카드는_이번_달_안내를_아직_보내지_않았으면_발송_대상이다() {
        Card card = issueCard();

        assertThat(card.shouldSendStatement(YearMonth.of(2026, 7))).isTrue();
    }

    @Test
    void 이번_달_안내를_이미_보냈으면_발송_대상이_아니다() {
        Card card = issueCard();
        card.markStatementSent(YearMonth.of(2026, 7));

        assertThat(card.shouldSendStatement(YearMonth.of(2026, 7))).isFalse();
        assertThat(card.getLastStatementSentMonth()).isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    void 다음_달에는_다시_발송_대상이_된다() {
        Card card = issueCard();
        card.markStatementSent(YearMonth.of(2026, 7));

        assertThat(card.shouldSendStatement(YearMonth.of(2026, 8))).isTrue();
    }

    @Test
    void 정지되거나_해지된_카드는_발송_대상이_아니다() {
        Card suspended = issueCard();
        suspended.suspend();
        Card cancelled = issueCard();
        cancelled.cancel();

        assertThat(suspended.shouldSendStatement(YearMonth.of(2026, 7))).isFalse();
        assertThat(cancelled.shouldSendStatement(YearMonth.of(2026, 7))).isFalse();
    }
}
