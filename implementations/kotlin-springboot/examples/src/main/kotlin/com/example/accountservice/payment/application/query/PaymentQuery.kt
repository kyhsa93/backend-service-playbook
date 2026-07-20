package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery

/**
 * 읽기 전용 포트 — Query Service가 의존하는 좁은 인터페이스(account/card의 `*Query` 컨벤션과 동일).
 * 쓰기 모델([com.example.accountservice.payment.domain.PaymentRepository])과 분리해, Query
 * Service가 savePayment 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제한다.
 *
 * `findPayments`는 PaymentRepository(쓰기 모델)가 정의하는 `PaymentFindQuery`를 그대로 재사용한다
 * (AccountQuery/CardQuery와 동일한 패턴) — 목록 조회(`GET /payments`)는 `ownerId`만으로, 단건 조회는
 * `paymentId` + `ownerId` + `take = 1`로 같은 메서드를 쓴다.
 */
interface PaymentQuery {
    fun findPayments(query: PaymentFindQuery): Pair<List<Payment>, Long>
}
