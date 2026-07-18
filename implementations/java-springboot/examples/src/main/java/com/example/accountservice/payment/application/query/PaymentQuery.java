package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.PaymentFindQuery;
import com.example.accountservice.payment.domain.PaymentsWithCount;

/**
 * Query Service 전용 읽기 인터페이스. 쓰기용 {@code PaymentRepository}(domain)와 분리된 좁은 계약이다. Query Service는 이
 * 인터페이스만 의존해야 한다 — {@code savePayment} 같은 쓰기 메서드를 노출하지 않는다(cqrs-pattern.md 참고).
 */
public interface PaymentQuery {
    PaymentsWithCount findPayments(PaymentFindQuery query);
}
