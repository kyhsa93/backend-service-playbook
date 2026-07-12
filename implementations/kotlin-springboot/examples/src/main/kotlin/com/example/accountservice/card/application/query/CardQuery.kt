package com.example.accountservice.card.application.query

import com.example.accountservice.card.domain.Card

/**
 * 읽기 전용 포트 — Query Service가 의존하는 좁은 인터페이스.
 *
 * root `cqrs-pattern.md`가 규정하는 `<Domain>Query` 네이밍·배치(application/query/)를 따른다
 * (#168에서 Account에 정리한 규칙을 Card도 처음부터 준수 — `CardQueryRepository` 같은 이름을 쓰지 않는다).
 * 쓰기 모델([com.example.accountservice.card.domain.CardRepository])과 분리해, Query Service가
 * save 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제한다. 실제 구현체(CardRepositoryImpl)는
 * 두 인터페이스를 모두 구현하지만, 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받는다.
 */
interface CardQuery {
    fun findByCardIdAndOwnerId(cardId: String, ownerId: String): Card?
}
