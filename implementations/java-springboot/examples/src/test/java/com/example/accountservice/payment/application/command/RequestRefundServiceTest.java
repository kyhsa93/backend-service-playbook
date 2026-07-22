package com.example.accountservice.payment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.payment.application.query.GetRefundResult;
import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import com.example.accountservice.payment.domain.RefundRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequestRefundServiceTest {

    @Mock private PaymentRepository paymentRepository;

    @Mock private RefundRepository refundRepository;

    private RequestRefundService service;

    @BeforeEach
    void setUp() {
        service = new RequestRefundService(paymentRepository, refundRepository);
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
    void 완료된_결제에_결제금액_이하의_환불이면_승인되고_저장된다() {
        Payment payment = completedPayment(1000);

        GetRefundResult result =
                service.request(
                        new RequestRefundCommand(
                                payment.getPaymentId(), 1000, "change of mind", "owner-1"));

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(refundRepository).saveRefund(any());
    }

    @Test
    void 환불_금액이_결제_금액을_초과하면_REJECTED로_저장되고_예외를_던지지_않는다() {
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
    void 완료되지_않은_결제에_대한_환불_요청은_REJECTED로_저장된다() {
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
    void 결제를_찾을_수_없으면_예외를_던지고_저장하지_않는다() {
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
