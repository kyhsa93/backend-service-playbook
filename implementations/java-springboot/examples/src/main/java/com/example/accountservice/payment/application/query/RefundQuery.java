package com.example.accountservice.payment.application.query;

import com.example.accountservice.payment.domain.RefundFindQuery;
import com.example.accountservice.payment.domain.RefundsWithCount;

public interface RefundQuery {
    RefundsWithCount findRefunds(RefundFindQuery query);
}
