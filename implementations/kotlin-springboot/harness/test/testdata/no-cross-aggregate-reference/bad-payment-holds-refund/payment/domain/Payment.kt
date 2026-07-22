package com.example.accountservice.payment.domain

class Payment private constructor() {
    var paymentId: String = ""
        private set

    // Violation — directly holds another Aggregate(Refund) as a field. Only ID references like paymentId are allowed.
    var refund: Refund? = null
        private set

    companion object {
        fun create(): Payment = Payment().apply { this.paymentId = "pay-1" }
    }
}
