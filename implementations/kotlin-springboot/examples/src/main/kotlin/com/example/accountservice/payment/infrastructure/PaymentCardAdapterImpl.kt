package com.example.accountservice.payment.infrastructure

import com.example.accountservice.card.application.query.CardQuery
import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardStatus
import com.example.accountservice.payment.application.adapter.CardAdapter
import com.example.accountservice.payment.application.adapter.CardView
import org.springframework.stereotype.Component

/**
 * The implementation of [CardAdapter] (ACL). Injects and calls the read port ([CardQuery]) exposed by
 * Card BC, and translates Card BC's model ([com.example.accountservice.card.domain.Card]·[CardStatus])
 * into the minimal shape Payment BC uses ([CardView]). It does not reference Card's write
 * Repository/domain methods.
 *
 * Card's "no card" signal is `CardQuery.findCards` returning an empty list (a single-record lookup is
 * also handled via `take = 1` + `firstOrNull()`), and this is propagated as-is into the `null` that the
 * Payment domain understands — Card's exception types (CardNotFoundException, etc.) do not leak into
 * the Payment layer.
 *
 * See the [com.example.accountservice.payment.infrastructure.PaymentAccountAdapterImpl] doc comment
 * for why the class name carries the `Payment` prefix — there is currently no other `CardAdapterImpl`
 * that would collide with this name, but the same prefix convention is applied consistently anyway.
 */
@Component
class PaymentCardAdapterImpl(
    private val cardQuery: CardQuery,
) : CardAdapter {
    override fun findCard(
        cardId: String,
        ownerId: String,
    ): CardView? {
        val (cards, _) = cardQuery.findCards(CardFindQuery(page = 0, take = 1, cardId = cardId, ownerId = ownerId))
        return cards.firstOrNull()?.let { card ->
            CardView(cardId = card.cardId, accountId = card.accountId, active = card.status == CardStatus.ACTIVE)
        }
    }
}
