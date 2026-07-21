package com.example.accountservice.payment.application.query;

/**
 * 특정 카드의 일정 기간 결제 사용 통계(건수·합계) — 카드 BC의 월간 카드 사용내역 안내(scheduling.md Feature 2)가 {@code
 * PaymentAdapter}(ACL) 경유로 이 결과를 읽는다. {@link PaymentQuery#summarizeCardUsage} 참고.
 */
public record PaymentUsageSummary(long count, long totalAmount) {}
