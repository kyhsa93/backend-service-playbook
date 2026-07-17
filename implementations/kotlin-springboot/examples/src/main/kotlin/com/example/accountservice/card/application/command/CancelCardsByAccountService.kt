package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import org.springframework.stereotype.Service

/**
 * Account BC의 `account.closed.v1` Integration Event에 대한 반응 유스케이스.
 *
 * 아직 해지되지 않은 카드(ACTIVE·SUSPENDED)만 해지하므로 재수신에 멱등하다. 이 Service 자신은
 * 트랜잭션 경계를 갖지 않는다 — outbox 드레인 루프를 감싼 상위 트랜잭션 안에서 호출된다
 * (domain-events.md, persistence.md의 트랜잭션 경계 규칙 참조).
 */
@Service
class CancelCardsByAccountService(
    private val cardRepository: CardRepository,
) {
    fun cancel(accountId: String) {
        val cards =
            cardRepository.findByAccountIdAndStatuses(
                accountId,
                listOf(CardStatus.ACTIVE, CardStatus.SUSPENDED),
            )
        for (card in cards) {
            card.cancel()
            cardRepository.save(card)
        }
    }
}
