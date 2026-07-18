package com.example.accountservice.payment.infrastructure;

import com.example.accountservice.card.application.query.CardQuery;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardStatus;
import com.example.accountservice.payment.application.adapter.CardAdapter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link CardAdapter}의 구현체(ACL). Card BC가 노출한 읽기 인터페이스({@link CardQuery})를 주입받아 호출하고, Card BC의 모델을
 * Payment BC가 쓰는 최소 형태({@link CardAdapter.CardView})로 번역한다. Card의 Repository/도메인 객체를 Payment의
 * Application·Domain 레이어로 노출하지 않는 유일한 경계 지점이다(card/infrastructure/AccountAdapterImpl.java와 동일한 구조).
 */
// PaymentCardAdapterImpl로 명명한 이유는 PaymentAccountAdapterImpl과 동일하다 — 향후 다른 BC가
// 같은 단순 클래스명(CardAdapterImpl)으로 또 다른 구현체를 추가할 여지를 남기지 않는다.
@Component
@RequiredArgsConstructor
public class PaymentCardAdapterImpl implements CardAdapter {

    private final CardQuery cardQuery;

    @Override
    public Optional<CardView> findCard(String cardId, String ownerId) {
        return cardQuery.findByCardIdAndOwnerId(cardId, ownerId).map(this::toCardView);
    }

    private CardView toCardView(Card card) {
        return new CardView(
                card.getCardId(), card.getAccountId(), card.getStatus() == CardStatus.ACTIVE);
    }
}
