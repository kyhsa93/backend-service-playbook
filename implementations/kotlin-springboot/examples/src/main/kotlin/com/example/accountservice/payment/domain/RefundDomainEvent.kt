package com.example.accountservice.payment.domain

import java.time.LocalDateTime

/**
 * Refund Aggregate가 발행하는 Domain Event 공통 계층 — [PaymentDomainEvent]와 동일한 이유로
 * `sealed interface`를 쓴다. 현재는 [RefundApprovedEvent] 하나뿐이지만, Refund 전용 계층을 두면
 * 향후 이벤트가 추가돼도 `when` 분기의 완전성 검사를 그대로 받는다.
 */
sealed interface RefundDomainEvent {
    val refundId: String
}

data class RefundApprovedEvent(
    override val refundId: String,
    val paymentId: String,
    val accountId: String,
    val ownerId: String,
    val amount: Long,
    val approvedAt: LocalDateTime,
) : RefundDomainEvent
