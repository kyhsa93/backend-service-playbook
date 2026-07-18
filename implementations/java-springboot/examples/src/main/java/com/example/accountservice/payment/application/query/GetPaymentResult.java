package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.Payment;
import java.time.LocalDateTime;

/**
 * Payment 조회 결과 — 결제 생성(CreatePaymentService)과 단건/목록 조회(GetPaymentService)가 모두 이 형태를 그대로 재사용한다
 * (nestjs 구현체의 {@code CreatePaymentResponseBody extends GetPaymentResult}와 동일하게, Payment는 Money 같은
 * 값 객체 변환이 없어 두 용도가 구조적으로 완전히 같으므로 별도의 CreatePaymentResult를 따로 두지 않는다).
 */
public record GetPaymentResult(
        String paymentId,
        String cardId,
        String accountId,
        String ownerId,
        long amount,
        String status,
        LocalDateTime createdAt) {

    public static GetPaymentResult from(Payment payment) {
        return new GetPaymentResult(
                payment.getPaymentId(),
                payment.getCardId(),
                payment.getAccountId(),
                payment.getOwnerId(),
                payment.getAmount(),
                payment.getStatus().name(),
                payment.getCreatedAt());
    }
}
