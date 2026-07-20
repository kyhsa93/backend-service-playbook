package com.example.accountservice.payment.domain

class Refund private constructor() {
    var refundId: String = ""
        private set

    var paymentId: String = ""
        private set

    companion object {
        fun create(paymentId: String): Refund =
            Refund().apply {
                this.refundId = "ref-1"
                this.paymentId = paymentId
            }
    }
}
