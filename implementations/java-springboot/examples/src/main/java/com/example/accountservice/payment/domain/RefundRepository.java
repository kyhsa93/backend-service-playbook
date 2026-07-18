package com.example.accountservice.payment.domain;

public interface RefundRepository {
    RefundsWithCount findRefunds(RefundFindQuery query);

    void saveRefund(Refund refund);
}
