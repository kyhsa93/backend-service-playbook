package com.example.accountservice.payment.application.command;

import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CancelPaymentService {

    private final PaymentRepository paymentRepository;

    public void cancel(CancelPaymentCommand command) {
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

        payment.cancel(command.reason());
        paymentRepository.savePayment(payment);
        // The Account BC subscribes to PaymentCancelledEvent -> payment.cancelled.v1 and runs the
        // compensating credit (the OutboxPoller/OutboxConsumer processes it asynchronously — this
        // method returns immediately after saving).
    }
}
