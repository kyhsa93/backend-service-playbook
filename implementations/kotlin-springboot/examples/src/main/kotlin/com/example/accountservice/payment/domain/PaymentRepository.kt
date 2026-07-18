package com.example.accountservice.payment.domain

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
)
