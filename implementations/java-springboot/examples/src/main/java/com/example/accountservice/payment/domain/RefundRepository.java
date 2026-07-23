package com.example.accountservice.payment.domain;

import java.time.LocalDateTime;

public interface RefundRepository {
    RefundsWithCount findRefunds(RefundFindQuery query);

    void saveRefund(Refund refund);

    /**
     * A dedicated aggregate query for {@code RefundFraudRiskScorer}'s feature assembly (see
     * application/command/RequestRefundService.java), the same reason {@code
     * PaymentQuery#summarizeCardUsage} exists on the read side — counting an owner's refund history
     * via {@link #findRefunds} and counting matches in the Application layer wouldn't scale once
     * history grows past a single page. Refund itself carries no {@code ownerId} (only {@code
     * paymentId}), so an implementation must join against Payment to filter by owner. {@code
     * status} is optional (a {@code null} value means no status filter); {@code createdAtFrom} is
     * an inclusive lower bound.
     *
     * <p>Deliberately not named in the {@code find<Noun>s} form — like {@code summarizeCardUsage},
     * it returns an aggregate count rather than a page of Refund records, so it doesn't fit the
     * list-lookup shape {@code find<Noun>s} is for (repository-pattern.md; {@code
     * harness/RepositoryNaming}'s blocklist only forbids a bare {@code count*}-prefixed method, not
     * a semantically-named aggregate query like this one).
     */
    long summarizeRefundsByOwner(String ownerId, LocalDateTime createdAtFrom, RefundStatus status);
}
