package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundEligibilityService
import com.example.accountservice.payment.domain.RefundRepository
import org.springframework.stereotype.Service

/**
 * A judgment that neither Aggregate can make alone (comparing the original payment's status + the
 * refund amount) is delegated to [RefundEligibilityService] (a Domain Service) and coordinated by this
 * Application layer, which loads both the Payment and Refund Aggregates together.
 *
 * [refundEligibilityService] is a stateless, pure Domain Service, so instead of registering it as a
 * Spring bean, this Service holds it by instantiating it directly (a constructor call in Kotlin) —
 * the same reasoning as the nestjs reference.
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
            // A refund rejection is a valid state transition from the domain's point of view (not
            // invalid input, but a conclusion reached by coordinating the two Aggregates) — so this
            // method does not throw; it returns the Refund saved as REJECTED as-is. The Interface
            // layer responds to this with 201 + status:REJECTED, not an error.
            refund.reject(decision.reason ?: "The refund request was rejected.")
        }

        refundRepository.saveRefund(refund)
        // RefundApprovedEvent → the Account BC subscribes to refund.approved.v1 and executes the
        // refund credit. When rejected there is no Domain Event, so nothing is written to the Outbox.
        // After saving, this returns immediately — Outbox → queue publish/receive is handled
        // independently by OutboxPoller/OutboxConsumer (no synchronous drain, domain-events.md).

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
