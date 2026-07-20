package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundFindQuery

/**
 * 읽기 전용 포트. Refund 테이블 자체는 ownerId를 갖지 않는다(Refund는 paymentId로만 원 결제를
 * 참조한다) — 소유권 검증은 [GetRefundsService]가 [PaymentQuery]로 Payment를 먼저 조회해 확인한다.
 *
 * `findRefunds`는 RefundRepository(쓰기 모델)가 정의하는 `RefundFindQuery`를 그대로 재사용한다
 * (AccountQuery/CardQuery/PaymentQuery와 동일한 패턴).
 */
interface RefundQuery {
    fun findRefunds(query: RefundFindQuery): Pair<List<Refund>, Long>
}
