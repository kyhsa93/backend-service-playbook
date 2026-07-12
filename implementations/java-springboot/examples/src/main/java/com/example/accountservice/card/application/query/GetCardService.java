package com.example.accountservice.card.application.query;

import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetCardService {

    private final CardQuery cardQuery;

    public GetCardResult getCard(String cardId, String requesterId) {
        Card card = cardQuery.findByCardIdAndOwnerId(cardId, requesterId)
                .orElseThrow(() -> new CardException(CardException.ErrorCode.CARD_NOT_FOUND, "카드를 찾을 수 없습니다."));
        return new GetCardResult(
                card.getCardId(),
                card.getAccountId(),
                card.getOwnerId(),
                card.getBrand(),
                card.getStatus().name(),
                card.getCreatedAt());
    }
}
