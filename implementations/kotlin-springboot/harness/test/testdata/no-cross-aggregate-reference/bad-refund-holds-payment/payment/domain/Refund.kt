package com.example.accountservice.payment.domain

class Refund private constructor() {
    var refundId: String = ""
        private set

    var paymentId: String = ""
        private set

    // Violation — directly holds another Aggregate(Payment) as a field. Only ID references like paymentId are allowed.
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
