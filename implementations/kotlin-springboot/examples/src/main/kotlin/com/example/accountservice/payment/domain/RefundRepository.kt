package com.example.accountservice.payment.domain

/**
 * Refund 쓰기 모델 포트. 목록 조회는 항상 원 결제(Payment)의 소유권을 먼저 확인해야 하므로
 * [com.example.accountservice.payment.application.query.RefundQuery]가 전담하지만, `findRefunds`
 * 시그니처 자체(및 그 기반이 되는 [RefundFindQuery])는 root `repository-pattern.md`가 요구하는
 * `find<Noun>s` 통일 규칙에 맞춰 여기서 정의하고 `RefundQuery`가 재사용한다(PaymentRepository/
 * PaymentQuery가 `PaymentFindQuery`를 공유하는 것과 동일한 패턴).
 */
interface RefundRepository {
    fun findRefunds(query: RefundFindQuery): Pair<List<Refund>, Long>

    fun saveRefund(refund: Refund)
}

/**
 * Refund는 ownerId를 갖지 않는다(원 결제를 `paymentId`로만 참조) — 소유권 검증은
 * `GetRefundsService`가 `PaymentQuery`로 Payment를 먼저 조회해 확인한다.
 */
data class RefundFindQuery(
    val page: Int,
    val take: Int,
    val refundId: String? = null,
    val paymentId: String? = null,
)
