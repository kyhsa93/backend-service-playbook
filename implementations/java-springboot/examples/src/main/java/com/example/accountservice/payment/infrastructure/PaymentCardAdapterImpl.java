package com.example.accountservice.payment.infrastructure;

import com.example.accountservice.card.application.query.CardQuery;
import com.example.accountservice.card.domain.Card;
import com.example.accountservice.card.domain.CardFindQuery;
import com.example.accountservice.card.domain.CardStatus;
import com.example.accountservice.payment.application.adapter.CardAdapter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The implementation (ACL) of {@link CardAdapter}. Injects and calls the read interface ({@link
 * CardQuery}) exposed by the Card BC, and translates the Card BC's model into the minimal shape the
 * Payment BC uses ({@link CardAdapter.CardView}). This is the sole boundary point that keeps Card's
 * Repository/domain objects from being exposed to Payment's Application/Domain layers (the same
 * structure as card/infrastructure/AccountAdapterImpl.java).
 */
// The reason it's named PaymentCardAdapterImpl is the same as PaymentAccountAdapterImpl — this
// leaves no room for another BC to later add a different implementation under the same simple
// class name (CardAdapterImpl).
@Component
@RequiredArgsConstructor
public class PaymentCardAdapterImpl implements CardAdapter {

    private final CardQuery cardQuery;

    @Override
    public Optional<CardView> findCard(String cardId, String ownerId) {
        return cardQuery
                .findCards(new CardFindQuery(0, 1, cardId, ownerId, null, null))
                .cards()
                .stream()
                .findFirst()
                .map(this::toCardView);
    }

    private CardView toCardView(Card card) {
        return new CardView(
                card.getCardId(), card.getAccountId(), card.getStatus() == CardStatus.ACTIVE);
    }
}
