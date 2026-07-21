package com.example.accountservice.card.domain

/**
 * Card 쓰기 모델 포트 — Command Service가 의존하는 인터페이스.
 *
 * 읽기 전용 조회는 [com.example.accountservice.card.application.query.CardQuery]로 분리한다
 * (cqrs-pattern.md). 실제 구현체(CardRepositoryImpl)는 두 인터페이스를 모두 구현하지만,
 * 각 Service는 자신에게 필요한 인터페이스 타입으로만 주입받는다.
 *
 * root `repository-pattern.md`의 `find<Noun>s` 통일 규칙에 맞춰 목록/단건/조건부 조회를 모두
 * `findCards` 하나로 처리한다(account/payment의 `AccountRepository`/`PaymentRepository`와 동일한
 * 형태). `CancelCardsByAccountService`/`SuspendCardsByAccountService`는 계좌의 특정 상태 카드
 * 전체가 필요하므로 `take`를 충분히 크게 준다(REST 페이지네이션 대상이 아닌 내부 반응 유스케이스).
 */
interface CardRepository {
    fun findCards(query: CardFindQuery): Pair<List<Card>, Long>

    fun saveCard(card: Card)
}

data class CardFindQuery(
    val page: Int,
    val take: Int,
    val cardId: String? = null,
    val ownerId: String? = null,
    val accountId: String? = null,
    val status: List<CardStatus>? = null,
    // SendMonthlyCardStatementsService 전용 필터 — 이미 이번 달("yyyy-MM") 명세서를 보낸 카드
    // (lastStatementSentMonth = 이 값)를 조회 단계에서 걸러낸다.
    val excludeStatementMonth: String? = null,
)
