package com.example.accountservice.card.application.command;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardRepository;
import com.example.accountservice.card.domain.CardStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Account BC의 {@code account.closed.v1} Integration Event에 대한 반응 유스케이스. 아직 해지되지 않은
 * 카드(ACTIVE·SUSPENDED)만 해지하므로 재수신에 멱등하다.
 */
@Service
@RequiredArgsConstructor
public class CancelCardsByAccountService {

    private final CardRepository cardRepository;

    public void cancel(CancelCardsByAccountCommand command) {
        List<Card> cards =
                cardRepository.findByAccountIdAndStatusIn(
                        command.accountId(), List.of(CardStatus.ACTIVE, CardStatus.SUSPENDED));
        for (Card card : cards) {
            card.cancel();
            cardRepository.save(card);
        }
    }
}
