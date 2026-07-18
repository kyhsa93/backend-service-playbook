package com.example.accountservice.payment.application.query;

import java.util.List;

public record GetPaymentsResult(List<GetPaymentResult> payments, long count) {}
