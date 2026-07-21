package com.example.accountservice.card.application.adapter

import java.time.LocalDateTime

/**
 * Payment BC를 동기 조회하기 위한 Adapter 인터페이스 (Anticorruption Layer).
 *
 * 매월 카드 사용내역 명세서(SendMonthlyCardStatementsService)가 카드별 결제 건수/합계를 집계할 때
 * 사용한다 — Card BC가 이미 Account를 이 방식으로 조회하고 있는 것과 동일한 패턴
 * ([com.example.accountservice.card.application.adapter.AccountAdapter])을 재사용한다. 반환
 * 타입은 Payment BC의 `Payment`/`PaymentStatus`를 노출하지 않고 Card BC가 필요로 하는 최소 형태
 * ([PaymentSummaryView])로 번역한다. 실제 번역은
 * [com.example.accountservice.card.infrastructure.CardPaymentAdapterImpl]가 담당한다.
 */
interface PaymentAdapter {
    fun summarizePayments(
        cardId: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): PaymentSummaryView
}

data class PaymentSummaryView(
    val count: Long,
    val totalAmount: Long,
)
