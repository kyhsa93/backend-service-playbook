package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import java.time.LocalDateTime;

/**
 * A read-only interface dedicated to the Query Service. A narrow contract separated from the
 * write-side {@code PaymentRepository} (domain). The Query Service should depend only on this
 * interface — it does not expose write methods such as {@code savePayment} (see cqrs-pattern.md).
 */
public interface PaymentQuery {
    PaymentsWithCount findPayments(PaymentFindQuery query);

    /**
     * Card usage statistics (count/total) for a given card — the Card BC's monthly card usage
     * notification (scheduling.md Feature 2) calls this method via {@code PaymentAdapter} (ACL,
     * card/application/adapter/PaymentAdapter). Only COMPLETED payments are aggregated —
     * PENDING/FAILED/CANCELLED were never actually charged, so they are excluded from the
     * statistics. {@code to} is an exclusive upper bound.
     */
    PaymentUsageSummary summarizeCardUsage(String cardId, LocalDateTime from, LocalDateTime to);
}
