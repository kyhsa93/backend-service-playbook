package com.example.accountservice.payment.domain;

import java.util.List;

/**
 * {@code findPayments} 조회 결과 — 목록과 총 개수를 함께 반환한다. 단건 조회도 이 타입을 재사용한다: {@code
 * PaymentFindQuery.take}를 1로 설정해 호출한 뒤 {@code payments()}의 첫 번째 결과를 꺼내 쓴다(repository-pattern.md
 * 참고).
 */
public record PaymentsWithCount(List<Payment> payments, long count) {}
