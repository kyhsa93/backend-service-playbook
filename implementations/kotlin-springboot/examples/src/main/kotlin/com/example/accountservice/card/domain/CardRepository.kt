package com.example.accountservice.card.domain

/**
 * Card 쓰기 모델 포트 — Command Service가 의존하는 인터페이스.
 *
 * 읽기 전용 조회는 [com.example.accountservice.card.application.query.CardQuery]로 분리한다
 * (cqrs-pattern.md). 실제 구현체(CardRepositoryImpl)는 두 인터페이스를 모두 구현하지만,
 * 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받는다.
 */
interface CardRepository {
    fun save(card: Card)
    fun findByAccountIdAndStatuses(accountId: String, statuses: List<CardStatus>): List<Card>
}
