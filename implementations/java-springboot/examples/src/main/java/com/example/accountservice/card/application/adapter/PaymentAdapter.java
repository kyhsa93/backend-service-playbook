package com.example.accountservice.card.application.adapter;

import java.time.LocalDateTime;

/**
 * Adapter interface (ACL) for synchronously querying the Payment BC. The monthly card usage
 * statement notification (scheduling.md Feature 2) needs to look up payment history from the
 * Payment BC to calculate each card's transaction count and total, so a synchronous Adapter pattern
 * is used (see cross-domain.md — same reasoning as card/application/adapter/AccountAdapter).
 *
 * <p>The return type is translated into the minimal aggregated shape the Card BC needs ({@link
 * UsageSummary}) — it does not expose the Payment BC's Payment entity or its status enum.
 */
public interface PaymentAdapter {

    UsageSummary summarizeUsage(String cardId, LocalDateTime from, LocalDateTime to);

    /** The minimal usage-summary view owned by the Card BC. */
    record UsageSummary(long count, long totalAmount) {}
}
