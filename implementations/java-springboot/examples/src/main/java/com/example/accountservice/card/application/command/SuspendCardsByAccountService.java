package com.example.accountservice.card.application.command;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Account BC의 {@code account.suspended.v1} Integration Event에 대한 반응 유스케이스.
 * at-least-once 전달을 전제로 멱등하게 구현한다 — ACTIVE 카드만 골라 정지하므로
 * 같은 이벤트가 재수신되어도(이미 정지된 카드) 아무 일도 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SuspendCardsByAccountService {

    private final CardRepository cardRepository;

    public void suspend(SuspendCardsByAccountCommand command) {
        List<Card> cards = cardRepository.findByAccountIdAndStatusIn(
                command.accountId(), List.of(CardStatus.ACTIVE));
        for (Card card : cards) {
            card.suspend();
            cardRepository.save(card);
        }
    }
}
