package com.example.accountservice.payment.domain;

/**
 * Payment Aggregate의 쓰기용 Repository 계약(domain 소유). 읽기 전용 조회는 별도의 application/query/PaymentQuery
 * 인터페이스로 분리한다(cqrs-pattern.md 참고) — 다만 Command 유스케이스(결제취소/환불요청)도 소유권 검증을 겸한 단건 조회가 필요하므로 {@code
 * findPayments}는 두 인터페이스에 동일한 시그니처로 존재한다(account/domain/AccountRepository와 동일한 패턴).
 */
public interface PaymentRepository {
    PaymentsWithCount findPayments(PaymentFindQuery query);

    void savePayment(Payment payment);
}
