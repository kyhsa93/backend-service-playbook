package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentException;
import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.RefundFindQuery;
import com.example.accountservice.payment.domain.RefundsWithCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Refund table itself has no ownerId (Refund references the original payment only via
 * paymentId) — ownership verification is done by first querying the original payment through {@link
 * PaymentQuery}. This is the same pattern as account's {@code GetTransactionsService}, which
 * confirms account ownership before querying transaction history.
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
                                        "Payment not found."));

        RefundsWithCount result =
                refundQuery.findRefunds(new RefundFindQuery(page, take, null, paymentId));
        return new GetRefundsResult(
                result.refunds().stream().map(GetRefundResult::from).toList(), result.count());
    }
}
