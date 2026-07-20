package com.example.accountservice.payment.domain

class Refund private constructor() {
    var refundId: String = ""
        private set

    var paymentId: String = ""
        private set

    // 위반 — 다른 Aggregate(Payment)를 필드로 직접 보유. paymentId 같은 ID 참조만 허용된다.
    var payment: Payment? = null
        private set

    companion object {
        fun create(paymentId: String): Refund =
            Refund().apply {
                this.refundId = "ref-1"
                this.paymentId = paymentId
            }
    }
}
