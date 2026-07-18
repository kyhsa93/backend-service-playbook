package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.RefundFindQuery;
import com.example.accountservice.payment.domain.RefundsWithCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund 테이블 자체는 ownerId를 갖지 않는다(Refund는 paymentId로만 원 결제를 참조한다) — 소유권 검증은 {@link PaymentQuery}로 원
 * 결제를 먼저 조회해 확인한다. account의 {@code GetTransactionsService}가 계좌 소유권을 먼저 확인한 뒤 거래 내역을 조회하는 것과 동일한
 * 패턴이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetRefundsService {

    private final PaymentQuery paymentQuery;
    private final RefundQuery refundQuery;

    public GetRefundsResult getRefunds(String paymentId, String requesterId, int page, int take) {
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

        RefundsWithCount result =
                refundQuery.findRefunds(new RefundFindQuery(page, take, null, paymentId));
        return new GetRefundsResult(
                result.refunds().stream().map(GetRefundResult::from).toList(), result.count());
    }
}
