package com.example.accountservice.payment.domain

class Payment private constructor() {
    var paymentId: String = ""
        private set

    // 위반 — 다른 Aggregate(Refund)를 필드로 직접 보유. paymentId 같은 ID 참조만 허용된다.
    var refund: Refund? = null
        private set

    companion object {
        fun create(): Payment = Payment().apply { this.paymentId = "pay-1" }
    }
}
