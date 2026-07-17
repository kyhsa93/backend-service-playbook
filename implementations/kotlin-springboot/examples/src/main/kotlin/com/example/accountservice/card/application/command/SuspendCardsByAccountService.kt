package com.example.accountservice.card.application.command

import com.example.accountservice.card.domain.CardRepository
import com.example.accountservice.card.domain.CardStatus
import org.springframework.stereotype.Service

/**
 * Account BC의 `account.suspended.v1` Integration Event에 대한 반응 유스케이스.
 *
 * at-least-once 전달을 전제로 멱등하게 구현한다 — ACTIVE 카드만 골라 정지하므로 같은 이벤트가
 * 재수신되어도(이미 정지된 카드) 아무 일도 하지 않는다. 이 Service 자신은 트랜잭션 경계를 갖지
 * 않는다 — outbox 드레인 루프를 감싼 상위 트랜잭션 안에서 호출된다(domain-events.md,
 * persistence.md의 트랜잭션 경계 규칙 참조).
 */
@Service
class SuspendCardsByAccountService(
    private val cardRepository: CardRepository,
) {
    fun suspend(accountId: String) {
        val cards = cardRepository.findByAccountIdAndStatuses(accountId, listOf(CardStatus.ACTIVE))
        for (card in cards) {
            card.suspend()
            cardRepository.save(card)
        }
    }
}
