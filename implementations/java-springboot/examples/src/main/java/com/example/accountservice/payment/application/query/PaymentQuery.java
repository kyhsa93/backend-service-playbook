package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentsWithCount;
import java.time.LocalDateTime;

/**
 * Query Service 전용 읽기 인터페이스. 쓰기용 {@code PaymentRepository}(domain)와 분리된 좁은 계약이다. Query Service는 이
 * 인터페이스만 의존해야 한다 — {@code savePayment} 같은 쓰기 메서드를 노출하지 않는다(cqrs-pattern.md 참고).
 */
public interface PaymentQuery {
    PaymentsWithCount findPayments(PaymentFindQuery query);

    /**
     * 카드 사용내역 통계(건수·합계) — Card BC의 월간 카드 사용내역 안내(scheduling.md Feature 2)가 {@code
     * PaymentAdapter}(ACL, card/application/adapter/PaymentAdapter) 경유로 이 메서드를 호출한다. 완료(COMPLETED)
     * 상태 결제만 집계한다 — PENDING/FAILED/CANCELLED는 실제로 청구되지 않았으므로 통계에서 제외한다. {@code to}는 배타적(exclusive)
     * 상한이다.
     */
    PaymentUsageSummary summarizeCardUsage(String cardId, LocalDateTime from, LocalDateTime to);
}
