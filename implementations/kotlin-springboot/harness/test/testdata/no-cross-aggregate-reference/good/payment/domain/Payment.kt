package com.example.accountservice.payment.domain

class Payment private constructor() {
    var paymentId: String = ""
        private set

    companion object {
        fun create(): Payment = Payment().apply { this.paymentId = "pay-1" }
    }
}
