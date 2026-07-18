package com.example.accountservice.payment.domain

/**
 * Refund 쓰기 모델 포트. 목록 조회는 항상 원 결제(Payment)의 소유권을 먼저 확인해야 하므로
 * [com.example.accountservice.payment.application.query.RefundQuery]가 전담하고, 이 인터페이스는
 * 저장(`saveRefund`)만 정의한다.
 */
interface RefundRepository {
    fun saveRefund(refund: Refund)
}
