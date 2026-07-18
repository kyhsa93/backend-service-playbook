package com.example.accountservice.payment.application.adapter

/**
 * Account BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).
 *
 * 결제 가능 여부(계좌 활성 여부 + 잔액 충분 여부)를 현재 요청 안에서 즉시 확인해야 하므로 동기
 * Adapter 패턴을 사용한다. 실제 차감은 이 동기 조회의 몫이 아니다 — `payment.completed.v1`
 * Integration Event를 Account BC가 구독해 비동기로 수행한다(cross-domain.md의 "동기=조회,
 * 비동기 Integration Event=상태변경" 원칙). Card BC의
 * [com.example.accountservice.card.application.adapter.AccountAdapter]와 이름은 같지만 패키지가
 * 달라 별개 타입이다 — Payment는 잔액(balanceAmount)까지 필요하므로 [AccountView]의 형태가 다르다.
 */
interface AccountAdapter {
    fun findAccount(
        accountId: String,
        ownerId: String,
    ): AccountView?
}

data class AccountView(
    val accountId: String,
    val active: Boolean,
    val balanceAmount: Long,
    val currency: String,
)
