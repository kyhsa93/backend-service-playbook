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
    void issuing_starts_as_ACTIVE() {
        Card card = issueCard();

        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.getAccountId()).isEqualTo("account-1");
        assertThat(card.getOwnerId()).isEqualTo("owner-1");
        assertThat(card.getBrand()).isEqualTo("VISA");
    }

    @Test
    void card_id_is_a_32_character_hex_string_with_no_hyphens() {
        Card card = issueCard();

        assertThat(card.getCardId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void suspending_an_active_card_moves_to_SUSPENDED() {
        Card card = issueCard();

        card.suspend();

        assertThat(card.getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    void throws_exception_when_suspending_an_already_suspended_card() {
        Card card = issueCard();
        card.suspend();

        assertThatThrownBy(card::suspend)
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CARD_ALREADY_SUSPENDED);
    }

    @Test
    void throws_exception_when_suspending_a_cancelled_card() {
        Card card = issueCard();
        card.cancel();

        assertThatThrownBy(card::suspend)
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CANCELLED_CARD_CANNOT_BE_SUSPENDED);
    }

    @Test
    void cancelling_an_active_card_moves_to_CANCELLED() {
        Card card = issueCard();

        card.cancel();

        assertThat(card.getStatus()).isEqualTo(CardStatus.CANCELLED);
    }

    @Test
    void cancelling_a_suspended_card_moves_to_CANCELLED() {
        Card card = issueCard();
        card.suspend();

        card.cancel();

        assertThat(card.getStatus()).isEqualTo(CardStatus.CANCELLED);
    }

    @Test
    void throws_exception_when_cancelling_an_already_cancelled_card() {
        Card card = issueCard();
        card.cancel();

        assertThatThrownBy(card::cancel)
                .isInstanceOf(CardException.class)
                .extracting(e -> ((CardException) e).code())
                .isEqualTo(CardException.ErrorCode.CARD_ALREADY_CANCELLED);
    }

    @Test
    void an_active_card_is_a_send_target_when_this_months_notice_has_not_been_sent_yet() {
        Card card = issueCard();

        assertThat(card.shouldSendStatement(YearMonth.of(2026, 7))).isTrue();
    }

    @Test
    void is_not_a_send_target_when_this_months_notice_was_already_sent() {
        Card card = issueCard();
        card.markStatementSent(YearMonth.of(2026, 7));

        assertThat(card.shouldSendStatement(YearMonth.of(2026, 7))).isFalse();
        assertThat(card.getLastStatementSentMonth()).isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    void becomes_a_send_target_again_next_month() {
        Card card = issueCard();
        card.markStatementSent(YearMonth.of(2026, 7));

        assertThat(card.shouldSendStatement(YearMonth.of(2026, 8))).isTrue();
    }

    @Test
    void a_suspended_or_cancelled_card_is_not_a_send_target() {
        Card suspended = issueCard();
        suspended.suspend();
        Card cancelled = issueCard();
        cancelled.cancel();

        assertThat(suspended.shouldSendStatement(YearMonth.of(2026, 7))).isFalse();
        assertThat(cancelled.shouldSendStatement(YearMonth.of(2026, 7))).isFalse();
    }
}
