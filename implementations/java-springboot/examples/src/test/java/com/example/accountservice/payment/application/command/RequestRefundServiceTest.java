package com.example.accountservice.payment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.payment.application.query.GetRefundResult;
import com.example.accountservice.payment.application.service.RefundFraudRiskScorer;
import com.example.accountservice.payment.application.service.RefundReasonClassifier;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import com.example.accountservice.payment.domain.RefundReasonCategory;
import com.example.accountservice.payment.domain.RefundReasonClassification;
import com.example.accountservice.payment.domain.RefundRepository;
import com.example.accountservice.payment.domain.RefundStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RefundEligibilityService (a Domain Service) is a plain class, so it isn't mocked — this spec
 * verifies the flow where the Application layer loads both Repositories, classifies the reason via
 * the (mocked) RefundReasonClassifier Technical Service, summarizes the requester's refund history
 * via RefundRepository#summarizeRefundsByOwner and scores it via the (mocked) RefundFraudRiskScorer
 * Technical Service, delegates to the real judgment logic, and approves/rejects and saves the
 * Refund based on the result. Mocking both Technical Service interfaces — rather than hitting a
 * real LLM/ML model — is exactly the benefit described in domain-service.md: no external
 * dependency, no non-determinism, in this test.
 */
@ExtendWith(MockitoExtension.class)
class RequestRefundServiceTest {

    @Mock private PaymentRepository paymentRepository;

    @Mock private RefundRepository refundRepository;

    @Mock private RefundReasonClassifier refundReasonClassifier;

    @Mock private RefundFraudRiskScorer refundFraudRiskScorer;

    private RequestRefundService service;

    @BeforeEach
    void setUp() {
        service =
                new RequestRefundService(
                        paymentRepository,
                        refundRepository,
                        refundReasonClassifier,
                        refundFraudRiskScorer);
        lenient()
                .when(refundReasonClassifier.classify(any()))
                .thenReturn(
                        new RefundReasonClassification(
                                RefundReasonCategory.DEFECTIVE_PRODUCT, 0.1));
        // A safe default so tests not exercising the ML threshold branch aren't rejected by it.
        lenient().when(refundFraudRiskScorer.score(any())).thenReturn(0.0);
        lenient()
                .when(refundRepository.summarizeRefundsByOwner(anyString(), any(), any()))
                .thenReturn(0L);
    }

    private Payment completedPayment(long amount) {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", amount);
        payment.complete();
        when(paymentRepository.findPayments(
                        new PaymentFindQuery(0, 1, payment.getPaymentId(), "owner-1")))
                .thenReturn(new PaymentsWithCount(List.of(payment), 1));
        return payment;
    }

    @Test
    void approves_and_saves_when_refund_is_at_most_the_payment_amount_on_a_completed_payment() {
        Payment payment = completedPayment(1000);

        GetRefundResult result =
                service.request(
                        new RequestRefundCommand(
                                payment.getPaymentId(), 1000, "change of mind", "owner-1"));

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(refundRepository).saveRefund(any());
        verify(refundReasonClassifier).classify("change of mind");
        verify(refundFraudRiskScorer).score(any());
    }

    @Test
    void saves_as_REJECTED_without_throwing_when_refund_amount_exceeds_the_payment_amount() {
        Payment payment = completedPayment(1000);

        GetRefundResult result =
                service.request(
                        new RequestRefundCommand(
                                payment.getPaymentId(), 1001, "change of mind", "owner-1"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.decisionNote())
                .isEqualTo("The refund amount cannot exceed the payment amount.");
        verify(refundRepository).saveRefund(any());
    }

    @Test
    void saves_a_refund_request_as_REJECTED_for_a_payment_that_is_not_completed() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000); // PENDING
        when(paymentRepository.findPayments(
                        new PaymentFindQuery(0, 1, payment.getPaymentId(), "owner-1")))
                .thenReturn(new PaymentsWithCount(List.of(payment), 1));

        GetRefundResult result =
                service.request(
                        new RequestRefundCommand(
                                payment.getPaymentId(), 500, "change of mind", "owner-1"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.decisionNote())
                .isEqualTo("A refund can only be requested for a completed payment.");
    }

    @Test
    void saves_as_REJECTED_without_throwing_when_the_classifier_flags_high_fraud_risk() {
        Payment payment = completedPayment(1000);
        when(refundReasonClassifier.classify("suspicious reason"))
                .thenReturn(
                        new RefundReasonClassification(RefundReasonCategory.FRAUD_SUSPECTED, 0.95));

        GetRefundResult result =
                service.request(
                        new RequestRefundCommand(
                                payment.getPaymentId(), 500, "suspicious reason", "owner-1"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.decisionNote())
                .isEqualTo(
                        "This refund reason was flagged as high fraud risk and requires manual"
                                + " review.");
        verify(refundRepository).saveRefund(any());
    }

    @Test
    void saves_as_REJECTED_without_throwing_when_the_ml_fraud_risk_scorer_flags_high_risk() {
        Payment payment = completedPayment(1000);
        when(refundFraudRiskScorer.score(any())).thenReturn(0.9);

        GetRefundResult result =
                service.request(
                        new RequestRefundCommand(
                                payment.getPaymentId(), 500, "change of mind", "owner-1"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.decisionNote())
                .isEqualTo(
                        "This refund pattern was flagged as high risk by the fraud-risk model and"
                                + " requires manual review.");
        verify(refundRepository).saveRefund(any());
    }

    @Test
    void summarizes_the_requesters_refund_history_by_owner_including_a_rejected_only_count() {
        Payment payment = completedPayment(1000);
        when(refundRepository.summarizeRefundsByOwner(eq("owner-1"), any(), isNull()))
                .thenReturn(3L);
        when(refundRepository.summarizeRefundsByOwner(
                        eq("owner-1"), any(), eq(RefundStatus.REJECTED)))
                .thenReturn(1L);

        service.request(
                new RequestRefundCommand(payment.getPaymentId(), 500, "change of mind", "owner-1"));

        verify(refundRepository).summarizeRefundsByOwner(eq("owner-1"), any(), isNull());
        verify(refundRepository)
                .summarizeRefundsByOwner(eq("owner-1"), any(), eq(RefundStatus.REJECTED));
        verify(refundFraudRiskScorer).score(any());
    }

    @Test
    void throws_exception_and_does_not_save_when_payment_is_not_found() {
        when(paymentRepository.findPayments(new PaymentFindQuery(0, 1, "non-existent", "owner-1")))
                .thenReturn(new PaymentsWithCount(List.of(), 0));

        assertThatThrownBy(
                        () ->
                                service.request(
                                        new RequestRefundCommand(
                                                "non-existent", 500, "change of mind", "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_NOT_FOUND);
        verify(refundRepository, never()).saveRefund(any());
    }
}
