package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.application.service.RefundFraudRiskScorer
import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.Refund
import com.example.accountservice.payment.domain.RefundEligibilityService
import com.example.accountservice.payment.domain.RefundRepository
import com.example.accountservice.payment.domain.RefundRiskFeatures
import com.example.accountservice.payment.domain.RefundStatus
import com.example.accountservice.payment.domain.RefundSummaryQuery
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

private const val RISK_HISTORY_WINDOW_DAYS = 30L

/**
 * A judgment that neither Aggregate can make alone (comparing the original payment's status + the
 * refund amount + the LLM-classified reason's fraud-risk signal + the ML-scored history pattern) is
 * delegated to [RefundEligibilityService] (a Domain Service) and coordinated by this Application
 * layer, which loads both the Payment and Refund Aggregates together, classifies the reason via the
 * [RefundReasonClassifier] Technical Service, and scores the refund's history pattern via the
 * [RefundFraudRiskScorer] Technical Service.
 *
 * [refundEligibilityService] is a stateless, pure Domain Service, so instead of registering it as a
 * Spring bean, this Service holds it by instantiating it directly (a constructor call in Kotlin) —
 * the same reasoning as the nestjs reference. [refundReasonClassifier]/[refundFraudRiskScorer], unlike
 * [refundEligibilityService], wrap external I/O (an LLM call, an ML scoring call), so they're
 * constructor-injected rather than instantiated directly.
 */
@Service
class RequestRefundService(
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val refundReasonClassifier: RefundReasonClassifier,
    private val refundFraudRiskScorer: RefundFraudRiskScorer,
) {
    private val refundEligibilityService = RefundEligibilityService()

    fun requestRefund(command: RequestRefundCommand): RequestRefundResult {
        val (payments, _) =
            paymentRepository.findPayments(
                PaymentFindQuery(page = 0, take = 1, paymentId = command.paymentId, ownerId = command.requesterId),
            )
        val payment = payments.firstOrNull() ?: throw PaymentNotFoundException(command.paymentId)

        val refund = Refund.create(paymentId = payment.paymentId, amount = command.amount, reason = command.reason)
        val classification = refundReasonClassifier.classify(command.reason)

        val historyWindowStart = LocalDateTime.now().minusDays(RISK_HISTORY_WINDOW_DAYS)
        val refundSummary =
            refundRepository.summarizeRefundsByOwner(
                RefundSummaryQuery(ownerId = payment.ownerId, createdAtFrom = historyWindowStart),
            )
        val rejectedRefundSummary =
            refundRepository.summarizeRefundsByOwner(
                RefundSummaryQuery(
                    ownerId = payment.ownerId,
                    createdAtFrom = historyWindowStart,
                    status = listOf(RefundStatus.REJECTED),
                ),
            )
        val mlFraudRiskScore =
            refundFraudRiskScorer.score(
                RefundRiskFeatures(
                    refundCountLast30Days = refundSummary.count.toInt(),
                    rejectedRefundCountLast30Days = rejectedRefundSummary.count.toInt(),
                    refundToPaymentAmountRatio = refund.amount.toDouble() / payment.amount.toDouble(),
                    minutesSincePayment =
                        Duration
                            .between(payment.createdAt, LocalDateTime.now())
                            .toMinutes()
                            .coerceAtLeast(0)
                            .toDouble(),
                ),
            )

        val decision = refundEligibilityService.evaluate(payment, refund, classification, mlFraudRiskScore)
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
