package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queries the payment history list. {@code ownerId} is obtained only from the authenticated
 * requester (Authentication) — this codebase has no endpoint that trusts an owner id sent by the
 * client (the "list queries are scoped to the authenticated user" principle in api-response.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPaymentsService {

    private final PaymentQuery paymentQuery;

    public GetPaymentsResult getPayments(String requesterId, int page, int take) {
        PaymentsWithCount result =
                paymentQuery.findPayments(new PaymentFindQuery(page, take, null, requesterId));
        return new GetPaymentsResult(
                result.payments().stream().map(GetPaymentResult::from).toList(), result.count());
    }
}
