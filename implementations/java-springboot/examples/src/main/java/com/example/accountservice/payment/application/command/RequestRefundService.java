package com.example.accountservice.payment.application.command;

import com.example.accountservice.payment.application.query.GetRefundResult;
import com.example.accountservice.payment.application.service.RefundFraudRiskScorer;
import com.example.accountservice.payment.application.service.RefundReasonClassifier;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import com.example.accountservice.payment.domain.Refund;
import com.example.accountservice.payment.domain.RefundDecision;
import com.example.accountservice.payment.domain.RefundEligibilityService;
import com.example.accountservice.payment.domain.RefundReasonClassification;
import com.example.accountservice.payment.domain.RefundRepository;
import com.example.accountservice.payment.domain.RefundRiskFeatures;
import com.example.accountservice.payment.domain.RefundStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RequestRefundService {

    // How far back RefundFraudRiskScorer's feature assembly looks when summarizing the
    // requester's refund history (see RefundRiskFeatures.refundCountLast30Days/
    // rejectedRefundCountLast30Days).
    private static final int RISK_HISTORY_WINDOW_DAYS = 30;

    // RefundEligibilityService is a pure Domain Service with no framework annotations.
    // It is instantiated directly instead of being registered as a Spring bean (it's stateless
    // pure judgment logic, so reusing it per request is not a problem).
    private final RefundEligibilityService refundEligibilityService =
            new RefundEligibilityService();

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    // RefundReasonClassifier/RefundFraudRiskScorer are Technical Services (DI-bound to their real
    // implementations) — unlike RefundEligibilityService above, they wrap external I/O
    // (an LLM call / an ML model), so they're injected rather than `new`'d directly.
    private final RefundReasonClassifier refundReasonClassifier;
    private final RefundFraudRiskScorer refundFraudRiskScorer;

    public GetRefundResult request(RequestRefundCommand command) {
        Payment payment =
                paymentRepository
                        .findPayments(
                                new PaymentFindQuery(
                                        0, 1, command.paymentId(), command.requesterId()))
                        .payments()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.PAYMENT_NOT_FOUND,
                                                "Payment not found."));

        Refund refund = Refund.create(payment.getPaymentId(), command.amount(), command.reason());
        RefundReasonClassification classification =
                refundReasonClassifier.classify(command.reason());

        LocalDateTime historyWindowStart = LocalDateTime.now().minusDays(RISK_HISTORY_WINDOW_DAYS);
        long refundCountLast30Days =
                refundRepository.summarizeRefundsByOwner(
                        payment.getOwnerId(), historyWindowStart, null);
        long rejectedRefundCountLast30Days =
                refundRepository.summarizeRefundsByOwner(
                        payment.getOwnerId(), historyWindowStart, RefundStatus.REJECTED);
        double minutesSincePayment =
                Math.max(
                        0,
                        Duration.between(payment.getCreatedAt(), LocalDateTime.now()).toSeconds()
                                / 60.0);
        double mlFraudRiskScore =
                refundFraudRiskScorer.score(
                        new RefundRiskFeatures(
                                refundCountLast30Days,
                                rejectedRefundCountLast30Days,
                                (double) refund.getAmount() / payment.getAmount(),
                                minutesSincePayment));

        // A judgment that no single Aggregate alone can make (comparing the original payment's
        // state, the refund amount, and the two independent fraud-risk signals classified/scored
        // above) is delegated to RefundEligibilityService (a Domain Service) by this Application
        // layer, which has loaded both the Payment and Refund Aggregates together, classified the
        // refund reason, and summarized+scored the refund history pattern via the Technical
        // Services above to coordinate it.
        RefundDecision decision =
                refundEligibilityService.evaluate(
                        payment, refund, classification, mlFraudRiskScore);
        if (decision.approved()) {
            refund.approve(payment.getAccountId(), payment.getOwnerId());
        } else {
            // Rejecting a refund is a valid state transition from a domain perspective (it is not
            // an invalid input, but a conclusion reached by coordinating the two Aggregates) —
            // so this method does not throw; it returns the Refund saved as REJECTED as-is. The
            // Interface layer responds with 201 + status:REJECTED rather than an error.
            refund.reject(
                    decision.reason() != null
                            ? decision.reason()
                            : "The refund request was rejected.");
        }

        refundRepository.saveRefund(refund);
        // The Account BC subscribes to RefundApprovedEvent -> refund.approved.v1 and runs the
        // refund credit (the OutboxPoller/OutboxConsumer processes it asynchronously). There is no
        // Domain Event when the refund is rejected.
        return GetRefundResult.from(refund);
    }
}
