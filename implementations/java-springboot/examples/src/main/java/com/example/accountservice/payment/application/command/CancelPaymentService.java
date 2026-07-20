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
                                                "결제를 찾을 수 없습니다."));

        payment.cancel(command.reason());
        paymentRepository.savePayment(payment);
        // PaymentCancelledEvent → payment.cancelled.v1을 Account BC가 구독해 보상 크레딧을 실행한다
        // (OutboxPoller/OutboxConsumer가 비동기로 처리 — 이 메서드는 저장 후 곧바로 반환한다).
    }
}
