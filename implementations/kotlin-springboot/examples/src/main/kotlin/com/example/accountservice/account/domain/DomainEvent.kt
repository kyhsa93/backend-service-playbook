package com.example.accountservice.account.domain

/**
 * Account Aggregate가 발행하는 Domain Event 공통 계층.
 *
 * `data class`로만 흩어져 있던 6개 이벤트 타입을 이 `sealed interface`로 묶으면, 이벤트를 다루는
 * `when` 분기(추가될 향후 코드 포함)에서 컴파일러가 완전성(exhaustiveness)을 검사해준다 — 새 이벤트
 * 타입이 추가되는데 처리를 빠뜨리면 컴파일 타임에 잡힌다. `Account.pullDomainEvents()`가 반환하는
 * 컬렉션 타입도 `List<Any>` 대신 `List<DomainEvent>`를 쓴다(domain-events.md 1단계 참고).
 *
 * `accountId`/`email`은 모든 이벤트가 공통으로 갖는 필드라 여기서 계약으로 명시한다 — 이벤트별로
 * 달라지는 타임스탬프 필드명(`createdAt`/`suspendedAt`/`reactivatedAt`/`closedAt`)은 공통화하지
 * 않는다.
 */
sealed interface DomainEvent {
    val accountId: String
    val email: String
}
