package com.example.accountservice.card.application.adapter

/**
 * Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).
 *
 * 카드 발급 시 연결 계좌의 존재·활성 여부를 현재 요청 안에서 즉시 확인해야 하므로 동기 Adapter
 * 패턴을 사용한다 (cross-domain.md 참조). 반환 타입은 Account BC의 `AccountStatus`/`Account`를
 * 노출하지 않고 Card BC가 필요로 하는 최소 형태([AccountView])로 번역한다 — 상류(Account) 모델
 * 변경이 Card 도메인으로 누수되지 않게 하는 것이 ACL의 목적이다. 실제 번역은
 * [com.example.accountservice.card.infrastructure.AccountAdapterImpl]가 담당한다.
 */
interface AccountAdapter {
    fun findAccount(accountId: String, ownerId: String): AccountView?
}

data class AccountView(
    val accountId: String,
    val active: Boolean,
)
