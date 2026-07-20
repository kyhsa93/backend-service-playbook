package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundEligibilityService
import com.example.accountservice.payment.domain.RefundRepository
import org.springframework.stereotype.Service

/**
 * 어느 한 Aggregate만으로는 내릴 수 없는 판단(원 결제 상태 + 환불 금액 비교)을 Payment+Refund 두
 * Aggregate를 함께 로드한 이 Application 레이어가 [RefundEligibilityService](Domain Service)에
 * 위임해 조율한다.
 *
 * [refundEligibilityService]는 상태가 없는 순수 Domain Service라 Spring 빈으로 등록하지 않고
 * 이 Service가 직접 `new`(Kotlin에서는 생성자 호출)해 보유한다 — nestjs 레퍼런스와 동일한 이유다.
 */
@Service
class RequestRefundService(
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
) {
    private val refundEligibilityService = RefundEligibilityService()

    fun requestRefund(command: RequestRefundCommand): RequestRefundResult {
        val (payments, _) =
            paymentRepository.findPayments(
                PaymentFindQuery(page = 0, take = 1, paymentId = command.paymentId, ownerId = command.requesterId),
            )
        val payment = payments.firstOrNull() ?: throw PaymentNotFoundException(command.paymentId)

        val refund = Refund.create(paymentId = payment.paymentId, amount = command.amount, reason = command.reason)

        val decision = refundEligibilityService.evaluate(payment, refund)
        if (decision.approved) {
            refund.approve(payment.accountId, payment.ownerId)
        } else {
            // 환불 거부는 도메인 관점에서 유효한 상태 전이다(입력이 잘못된 것이 아니라 두 Aggregate를
            // 조율해 내린 결론) — 따라서 이 메서드는 throw하지 않고 REJECTED로 저장한 Refund를 그대로
            // 반환한다. Interface 레이어가 이를 에러가 아닌 201 + status:REJECTED로 응답한다.
            refund.reject(decision.reason ?: "환불 요청이 거부되었습니다.")
        }

        refundRepository.saveRefund(refund)
        // RefundApprovedEvent → refund.approved.v1을 Account BC가 구독해 환불 크레딧을 실행한다.
        // 거부된 경우에는 Domain Event가 없으므로 Outbox에 적재되는 것이 없다. 저장 후에는 여기서
        // 곧바로 반환한다 — Outbox → 큐 발행/수신은 OutboxPoller/OutboxConsumer가 독립적으로
        // 담당한다(동기 드레인 금지, domain-events.md).

        return RequestRefundResult(
            refundId = refund.refundId,
            paymentId = refund.paymentId,
            amount = refund.amount,
            reason = refund.reason,
            status = refund.status.name,
            decisionNote = refund.decisionNote,
            createdAt = refund.createdAt,
        )
    }
}
