package com.example.accountservice.payment.domain;

// Refund는 ownerId를 갖지 않는다(원 결제 소유권 검증은 Payment를 통해 먼저 이루어진다 — application/query/GetRefundsService
// 참고).
public record RefundFindQuery(int page, int take, String refundId, String paymentId) {}
