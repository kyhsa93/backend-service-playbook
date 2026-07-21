package com.example.accountservice.card.infrastructure

import com.example.accountservice.card.application.adapter.PaymentAdapter
import com.example.accountservice.card.application.adapter.PaymentSummaryView
import com.example.accountservice.payment.application.query.PaymentQuery
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentStatus
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * [PaymentAdapter]의 구현체(ACL). Payment BC가 공개한 읽기 포트([PaymentQuery])를 주입받아 호출하고,
 * Payment BC의 모델을 Card BC가 쓰는 최소 형태([PaymentSummaryView])로 번역한다. 완료(COMPLETED)된
 * 결제만 "사용내역"으로 집계한다 — PENDING/FAILED/CANCELLED는 실제로 청구되지 않았거나 취소된
 * 결제이므로 명세서에 포함하지 않는다.
 *
 * 클래스명에 `Card` 접두어를 붙인 이유: [com.example.accountservice.payment.infrastructure.PaymentAccountAdapterImpl]/
 * [com.example.accountservice.payment.infrastructure.PaymentCardAdapterImpl]와 동일한 컨벤션 —
 * Spring의 기본 빈 이름 생성이 패키지가 달라도 단순 클래스명만 보므로, 소비하는 BC 이름을 접두어로
 * 붙여 향후 다른 `PaymentAdapterImpl`과의 충돌을 미리 피한다.
 *
 * 실제 조회는 `findPayments`가 반환하는 목록을 그대로 합산한다 — 명세서 대상 기간(최근 30일, 최대
 * [MAX_PAYMENTS_PER_STATEMENT]건)은 REST 페이지네이션 대상이 아닌 내부 배치 집계이므로, 별도
 * 합계 전용 쿼리를 추가하지 않고 기존 `findPayments` 패턴을 그대로 재사용한다(card의
 * CancelCardsByAccountService가 `take`를 충분히 크게 주는 것과 같은 판단).
 */
@Component
class CardPaymentAdapterImpl(
    private val paymentQuery: PaymentQuery,
) : PaymentAdapter {
    override fun summarizePayments(
        cardId: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): PaymentSummaryView {
        val (payments, count) =
            paymentQuery.findPayments(
                PaymentFindQuery(
                    page = 0,
                    take = MAX_PAYMENTS_PER_STATEMENT,
                    cardId = cardId,
                    status = listOf(PaymentStatus.COMPLETED),
                    createdFrom = from,
                    createdTo = to,
                ),
            )
        return PaymentSummaryView(count = count, totalAmount = payments.sumOf { it.amount })
    }

    companion object {
        private const val MAX_PAYMENTS_PER_STATEMENT = 10_000
    }
}
