package com.example.accountservice.payment.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Refund Aggregate Root — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체.
 *
 * 원 결제(Payment)의 상태·금액에 대한 판단은 Refund 자신이 할 수 없다 —
 * [RefundEligibilityService](Domain Service)가 Payment+Refund 두 Aggregate를 함께 로드해 조율한
 * 결과([RefundDecision])를 Application 레이어로부터 받아 [approve]/[reject]가 호출된다.
 */
class Refund private constructor() {
    var refundId: String = ""
        private set

    var paymentId: String = ""
        private set

    var amount: Long = 0
        private set

    var reason: String = ""
        private set

    var status: RefundStatus = RefundStatus.REQUESTED
        private set

    var decisionNote: String? = null
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    private val domainEvents: MutableList<RefundDomainEvent> = mutableListOf()

    companion object {
        fun create(
            paymentId: String,
            amount: Long,
            reason: String,
        ): Refund =
            Refund().apply {
                this.refundId = generateId()
                this.paymentId = paymentId
                this.amount = amount
                this.reason = reason
                this.status = RefundStatus.REQUESTED
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Refund를 복원할 때 사용한다.
         */
        fun reconstitute(
            refundId: String,
            paymentId: String,
            amount: Long,
            reason: String,
            status: RefundStatus,
            decisionNote: String?,
            createdAt: LocalDateTime,
        ): Refund =
            Refund().apply {
                this.refundId = refundId
                this.paymentId = paymentId
                this.amount = amount
                this.reason = reason
                this.status = status
                this.decisionNote = decisionNote
                this.createdAt = createdAt
            }
    }

    /**
     * [accountId]/[ownerId]는 RefundEligibilityService의 판단이 아니라, 판단 이후 외부 BC에 전파할
     * Integration Event를 조립하기 위해 Application 레이어가 이미 로드해둔 Payment에서 얻어 넘기는
     * 참조 데이터일 뿐이다(Refund 자신의 필드로 상시 보관하지 않는다).
     */
    fun approve(
        accountId: String,
        ownerId: String,
    ) {
        if (status != RefundStatus.REQUESTED) throw RefundApproveRequiresRequestedRefundException()
        status = RefundStatus.APPROVED
        decisionNote = "환불이 승인되었습니다."
        domainEvents += RefundApprovedEvent(refundId, paymentId, accountId, ownerId, amount, LocalDateTime.now())
    }

    fun reject(reason: String) {
        if (status != RefundStatus.REQUESTED) throw RefundRejectRequiresRequestedRefundException()
        status = RefundStatus.REJECTED
        decisionNote = reason
    }

    /**
     * 현재는 refund.approved.v1을 Account BC가 구독해 크레딧을 실행하는 것으로 환불 처리가 끝나고,
     * 그 크레딧 성공을 Payment BC로 다시 알려주는 콜백 경로는 없다(REST 표면에 없음). Payment 도메인의
     * 완결된 상태 모델을 위해 메서드는 남겨두되(Domain 단위 테스트로 검증), 현재 어떤 Command도 이를
     * 호출하지 않는다 — [Payment.fail]과 같은 이유로 미연결 상태다.
     */
    fun complete() {
        if (status != RefundStatus.APPROVED) throw RefundCompleteRequiresApprovedRefundException()
        status = RefundStatus.COMPLETED
    }

    fun pullDomainEvents(): List<RefundDomainEvent> = domainEvents.toList().also { domainEvents.clear() }
}
