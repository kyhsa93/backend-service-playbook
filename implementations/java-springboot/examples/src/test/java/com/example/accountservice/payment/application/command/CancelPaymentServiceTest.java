package com.example.accountservice.payment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelPaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;

    private CancelPaymentService service;

    @BeforeEach
    void setUp() {
        service = new CancelPaymentService(paymentRepository);
    }

    @Test
    void cancelling_a_completed_payment_saves_it_as_CANCELLED() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000);
        payment.complete();
        when(paymentRepository.findPayments(
                        new PaymentFindQuery(0, 1, payment.getPaymentId(), "owner-1")))
                .thenReturn(new PaymentsWithCount(List.of(payment), 1));

        service.cancel(
                new CancelPaymentCommand(payment.getPaymentId(), "customer request", "owner-1"));

        assertThat(payment.getStatus().name()).isEqualTo("CANCELLED");
        verify(paymentRepository).savePayment(payment);
    }

    @Test
    void throws_exception_when_payment_is_not_found() {
        when(paymentRepository.findPayments(new PaymentFindQuery(0, 1, "non-existent", "owner-1")))
                .thenReturn(new PaymentsWithCount(List.of(), 0));

        assertThatThrownBy(
                        () ->
                                service.cancel(
                                        new CancelPaymentCommand(
                                                "non-existent", "customer request", "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void throws_exception_and_does_not_save_when_cancelling_a_PENDING_payment() {
        Payment payment = Payment.create("card-1", "account-1", "owner-1", 1000);
        when(paymentRepository.findPayments(
                        new PaymentFindQuery(0, 1, payment.getPaymentId(), "owner-1")))
                .thenReturn(new PaymentsWithCount(List.of(payment), 1));

        assertThatThrownBy(
                        () ->
                                service.cancel(
                                        new CancelPaymentCommand(
                                                payment.getPaymentId(),
                                                "customer request",
                                                "owner-1")))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).code())
                .isEqualTo(PaymentException.ErrorCode.PAYMENT_CANCEL_REQUIRES_COMPLETED_PAYMENT);
        verify(paymentRepository, never()).savePayment(any());
    }
}
