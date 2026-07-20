package com.example.accountservice.payment.application.query

import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetPaymentService(
    private val paymentQuery: PaymentQuery,
) {
    fun getPayment(
        paymentId: String,
        requesterId: String,
    ): GetPaymentResult {
        val (payments, _) =
            paymentQuery.findPayments(
                PaymentFindQuery(page = 0, take = 1, paymentId = paymentId, ownerId = requesterId),
            )
        val payment = payments.firstOrNull() ?: throw PaymentNotFoundException(paymentId)
        return GetPaymentResult(
            paymentId = payment.paymentId,
            cardId = payment.cardId,
            accountId = payment.accountId,
            ownerId = payment.ownerId,
            amount = payment.amount,
            status = payment.status.name,
            createdAt = payment.createdAt,
        )
    }
}
