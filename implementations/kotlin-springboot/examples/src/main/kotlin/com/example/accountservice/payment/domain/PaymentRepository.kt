package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * Payment 쓰기 모델 포트 — Command Service가 의존하는 인터페이스.
 *
 * 읽기 전용 조회는 [com.example.accountservice.payment.application.query.PaymentQuery]로 분리한다
 * (cqrs-pattern.md). 실제 구현체(PaymentRepositoryImpl)는 두 인터페이스를 모두 구현하지만, 각
 * Service는 자신에게 필요한 인터페이스 타입으로만 주입받는다.
 *
 * [findPayments]는 목록 조회가 아니라 소유권 확인이 필요한 단건 조회(`take = 1`)에만 쓰인다 —
 * `CancelPaymentService`/`RequestRefundService`가 커맨드 대상 Payment를 `paymentId` + `ownerId`로
 * 확인할 때 사용한다. 목록 조회(`GET /payments`)는 [PaymentQuery]가 전담한다.
 */
interface PaymentRepository {
    fun findPayments(query: PaymentFindQuery): Pair<List<Payment>, Long>

    fun savePayment(payment: Payment)
}

data class PaymentFindQuery(
    val page: Int,
    val take: Int,
    val paymentId: String? = null,
    val ownerId: String? = null,
    // Card BC의 PaymentAdapter(카드 사용내역 명세서 집계) 전용 필터 — cardId + 기간 + 상태로 좁혀서
    // "이 카드로 최근에 발생한 완료 결제"만 집계한다. 기존 REST 목록 조회(ownerId만 사용)에는 영향
    // 없다(모두 nullable, 기본값 없으면 필터가 적용되지 않음).
    val cardId: String? = null,
    val status: List<PaymentStatus>? = null,
    val createdFrom: LocalDateTime? = null,
    val createdTo: LocalDateTime? = null,
)
