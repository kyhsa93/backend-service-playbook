package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.Payment

/**
 * 읽기 전용 포트 — Query Service가 의존하는 좁은 인터페이스(account/card의 `*Query` 컨벤션과 동일).
 * 쓰기 모델([com.example.accountservice.payment.domain.PaymentRepository])과 분리해, Query
 * Service가 savePayment 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제한다.
 */
interface PaymentQuery {
    fun findByPaymentIdAndOwnerId(
        paymentId: String,
        ownerId: String,
    ): Payment?

    fun findByOwnerId(
        ownerId: String,
        page: Int,
        take: Int,
    ): List<Payment>

    fun countByOwnerId(ownerId: String): Long
}
