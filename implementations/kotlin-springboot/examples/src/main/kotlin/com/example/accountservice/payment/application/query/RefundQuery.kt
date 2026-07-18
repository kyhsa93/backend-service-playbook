package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.Refund

/**
 * 읽기 전용 포트. Refund 테이블 자체는 ownerId를 갖지 않는다(Refund는 paymentId로만 원 결제를
 * 참조한다) — 소유권 검증은 [GetRefundsService]가 [PaymentQuery]로 Payment를 먼저 조회해 확인한다.
 */
interface RefundQuery {
    fun findByPaymentId(
        paymentId: String,
        page: Int,
        take: Int,
    ): List<Refund>

    fun countByPaymentId(paymentId: String): Long
}
