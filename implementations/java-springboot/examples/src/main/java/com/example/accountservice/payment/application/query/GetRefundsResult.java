package com.example.accountservice.payment.application.query;

import java.util.List;

public record GetRefundsResult(List<GetRefundResult> refunds, long count) {}
