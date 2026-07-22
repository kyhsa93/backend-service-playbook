package com.example.accountservice.payment.application.query;

/**
 * Payment usage statistics (count/total) for a specific card over a given period — the Card BC's
 * monthly card usage notification (scheduling.md Feature 2) reads this result via {@code
 * PaymentAdapter} (ACL). See {@link PaymentQuery#summarizeCardUsage}.
 */
public record PaymentUsageSummary(long count, long totalAmount) {}
