package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Payment;
import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPaymentService {

    private final PaymentQuery paymentQuery;

    public GetPaymentResult getPayment(String paymentId, String requesterId) {
        Payment payment =
                paymentQuery
                        .findPayments(new PaymentFindQuery(0, 1, paymentId, requesterId))
                        .payments()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new PaymentException(
                                                PaymentException.ErrorCode.PAYMENT_NOT_FOUND,
                                                "결제를 찾을 수 없습니다."));
        return GetPaymentResult.from(payment);
    }
}
