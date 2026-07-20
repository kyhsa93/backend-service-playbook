package com.example.accountservice.payment.infrastructure

import com.example.accountservice.card.application.query.CardQuery
import com.example.accountservice.card.domain.CardFindQuery
import com.example.accountservice.card.domain.CardStatus
import com.example.accountservice.payment.application.adapter.CardAdapter
import com.example.accountservice.payment.application.adapter.CardView
import org.springframework.stereotype.Component

/**
 * [CardAdapter]의 구현체(ACL). Card BC가 공개한 읽기 포트([CardQuery])를 주입받아 호출하고,
 * Card BC의 모델([com.example.accountservice.card.domain.Card]·[CardStatus])을 Payment BC가 쓰는
 * 최소 형태([CardView])로 번역한다. Card의 쓰기 Repository/도메인 메서드는 참조하지 않는다.
 *
 * Card의 "카드 없음" 신호는 `CardQuery.findCards`가 빈 목록을 반환하는 것이며(단건 조회도
 * `take = 1` + `firstOrNull()`로 처리한다), 이를 그대로 Payment 도메인이 이해하는 `null`로
 * 전파한다 — Card의 예외 타입(CardNotFoundException 등)이 Payment 레이어로 누수되지 않는다.
 *
 * 클래스명에 `Payment` 접두어를 붙인 이유는
 * [com.example.accountservice.payment.infrastructure.PaymentAccountAdapterImpl] 문서 참고 —
 * 현재는 이 이름과 충돌하는 다른 `CardAdapterImpl`이 없지만, 같은 접두어 컨벤션을 일관되게 적용한다.
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
