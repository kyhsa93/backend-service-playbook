package com.example.accountservice.payment.application.command;

import com.example.accountservice.payment.domain.PaymentRepository;

public class CreatePaymentService {
    private final PaymentRepository paymentRepository;

    public CreatePaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }
}
