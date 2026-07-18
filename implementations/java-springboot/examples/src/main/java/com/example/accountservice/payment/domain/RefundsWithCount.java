package com.example.accountservice.payment.domain;

import java.util.List;

public record RefundsWithCount(List<Refund> refunds, long count) {}
